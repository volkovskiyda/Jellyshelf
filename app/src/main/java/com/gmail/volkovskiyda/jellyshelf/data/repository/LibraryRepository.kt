package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.local.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.data.local.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.data.local.VIRTUAL_CATEGORY_CONTINUE
import com.gmail.volkovskiyda.jellyshelf.data.local.VIRTUAL_CATEGORY_UNCATEGORIZED
import com.gmail.volkovskiyda.jellyshelf.data.local.VIRTUAL_CATEGORY_UNWATCHED
import com.gmail.volkovskiyda.jellyshelf.data.local.VIRTUAL_CATEGORY_WATCHED
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoCategoryCrossRef
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import androidx.room.withTransaction
import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.util.YoutubeId
import com.gmail.volkovskiyda.jellyshelf.util.escapeLikePattern
import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import com.gmail.volkovskiyda.jellyshelf.util.stripApiKey
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
import com.gmail.volkovskiyda.jellyshelf.util.yearMonthOf
import com.gmail.volkovskiyda.jellyshelf.util.yearOf
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncResult {
    /**
     * @param itemCount total Jellyfin items scanned.
     * @param matched videos with a parseable YouTube id (the library total).
     * @param indexed videos that also had a jellyshelf-index.json / yt-dlp metadata match.
     * @param categories distinct auto-categories produced.
     */
    data class Success(
        val itemCount: Int,
        val matched: Int,
        val indexed: Int,
        val categories: Int,
    ) : SyncResult
    data class Error(val message: String) : SyncResult
}

sealed interface PlaylistResult {
    data class Success(val name: String, val count: Int) : PlaylistResult
    data class Error(val message: String) : PlaylistResult
}

/** Outcome of an in-app yt-dlp metadata fetch for a single video. */
sealed interface FetchResult {
    data class Success(val title: String) : FetchResult
    data class Error(val message: String) : FetchResult
}

/** Progress of the bulk "fetch all missing" run, observable so it survives navigation. */
sealed interface BulkFetch {
    data object Idle : BulkFetch
    data class Running(val done: Int, val total: Int, val failed: Int) : BulkFetch
    data class Done(val total: Int, val failed: Int) : BulkFetch
}

class LibraryRepository(
    private val db: JellyshelfDatabase,
    private val jellyfin: JellyfinRepository,
    private val settings: SettingsRepository,
    private val ytDlp: YtDlpMetadataSource,
) {
    private val videoDao = db.videoDao()
    private val categoryDao = db.categoryDao()

    /**
     * Serializes every read-modify-write of library rows — sync (manual or from the periodic
     * worker), per-video metadata application and watch-state updates — so concurrent writers
     * can't clobber each other's changes with stale snapshots.
     */
    private val writeMutex = Mutex()

    /**
     * When this process last wrote a video's watch state locally. Sync's Jellyfin snapshot is
     * taken before it acquires [writeMutex], so a local write that lands during the (possibly
     * multi-second) fetch would otherwise be reverted to the server's pre-write state; the
     * merge keeps the local values whenever this stamp postdates the fetch start.
     */
    private val localWatchWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Long-running work (bulk fetch, playback reports) runs here so it outlives the screen
    // that started it.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _bulkFetch = MutableStateFlow<BulkFetch>(BulkFetch.Idle)
    val bulkFetch: StateFlow<BulkFetch> = _bulkFetch.asStateFlow()
    private var bulkJob: Job? = null

    fun observeVideos(): Flow<List<VideoEntity>> = videoDao.observeAll()

    /**
     * Videos filtered to [bucket] (all durations when null), with those matching [query] listed
     * first and the rest after it — a soft search, so nothing is hidden. Each group stays sorted
     * by file name.
     */
    fun searchVideos(query: String, bucket: DurationBucket?): Flow<List<VideoEntity>> =
        videoDao.search(
            escapeLikePattern(query),
            bucket?.minSeconds ?: 0L,
            bucket?.maxSeconds ?: Long.MAX_VALUE,
        )

    /** Videos in [categoryId], routing the "Others" virtual filters to live queries. */
    fun observeVideosByCategory(categoryId: String): Flow<List<VideoEntity>> = when (categoryId) {
        VIRTUAL_CATEGORY_UNCATEGORIZED -> videoDao.observeBySource(METADATA_SOURCE_JELLYFIN)
        VIRTUAL_CATEGORY_CONTINUE -> videoDao.observeContinueWatching()
        VIRTUAL_CATEGORY_UNWATCHED -> videoDao.observeUnwatched()
        VIRTUAL_CATEGORY_WATCHED -> videoDao.observeWatched()
        else -> videoDao.observeByCategory(categoryId)
    }

    fun observeVideo(youtubeId: String): Flow<VideoEntity?> = videoDao.observe(youtubeId)
    fun observeCategories(): Flow<List<CategoryWithCount>> = categoryDao.observeWithCounts()
    fun searchCategories(query: String): Flow<List<CategoryWithCount>> =
        categoryDao.searchWithCounts(escapeLikePattern(query))

    /**
     * The "Others" tab's virtual filters with live counts: Uncategorized (no yt-dlp/index metadata),
     * Continue watching, Unwatched, Watched. Empty filters are dropped.
     */
    fun observeOthers(): Flow<List<CategoryWithCount>> = combine(
        videoDao.countBySource(METADATA_SOURCE_JELLYFIN),
        videoDao.countContinueWatching(),
        videoDao.countUnwatched(),
        videoDao.countWatched(),
    ) { uncategorized, continueWatching, unwatched, watched ->
        listOf(
            virtualRow(VIRTUAL_CATEGORY_UNCATEGORIZED, "Uncategorized", uncategorized),
            virtualRow(VIRTUAL_CATEGORY_CONTINUE, "Continue watching", continueWatching),
            virtualRow(VIRTUAL_CATEGORY_UNWATCHED, "Unwatched", unwatched),
            virtualRow(VIRTUAL_CATEGORY_WATCHED, "Watched", watched),
        ).filter { it.videoCount > 0 }
    }

    private fun virtualRow(id: String, name: String, count: Int) = CategoryWithCount(
        category = CategoryEntity(id = id, name = name, type = CATEGORY_TYPE_OTHERS, createdAt = 0L),
        videoCount = count,
    )

    fun videoCount(): Flow<Int> = videoDao.count()

    /** Full sync: pull Jellyfin items + metadata index, merge, persist, auto-categorize. */
    suspend fun sync(): SyncResult {
        val s = settings.snapshot()
        if (!s.isConnected) return SyncResult.Error("Not connected. Set server URL, API key and user in Settings.")

        val fetchStartedAt = System.currentTimeMillis()
        val items = try {
            jellyfin.fetchAllItems(s.serverUrl, s.apiKey, s.userId, s.libraryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncResult.Error("Failed to load library: ${e.message}")
        }

        // A failed index fetch must stay distinguishable from an index with no entries:
        // [mergeVideo] keeps existing index-sourced metadata when the index was unavailable,
        // instead of degrading those rows to bare Jellyfin fields.
        var indexAvailable = false
        val index: Map<String, IndexEntry> = if (s.indexUrl.isNotBlank()) {
            try {
                jellyfin.fetchIndex(s.indexUrl).associateBy { it.id }.also { indexAvailable = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val now = System.currentTimeMillis()
        val serverBase = s.serverUrl.trim().removeSuffix("/")

        return writeMutex.withLock {
            // Existing rows, to honour newest-wins: a manual in-app yt-dlp fetch is kept over an
            // index entry unless the index entry is genuinely newer. Read inside the lock so no
            // other writer can slip between this snapshot and the upsert below.
            val existingById = videoDao.getAll().associateBy { it.youtubeId }
            val videos = items.mapNotNull { item ->
                val youtubeId = YoutubeId.fromPath(item.path) ?: return@mapNotNull null
                mergeVideo(
                    existing = existingById[youtubeId],
                    youtubeId = youtubeId,
                    item = item,
                    meta = index[youtubeId],
                    indexAvailable = indexAvailable,
                    serverBase = serverBase,
                    now = now,
                    // A local watch-state write that landed after the server snapshot was taken
                    // is newer than that snapshot — keep it.
                    keepLocalWatchState = (localWatchWrites[youtubeId] ?: 0L) > fetchStartedAt,
                )
            }.distinctBy { it.youtubeId }

            // Auto-categorize each video along several dimensions: channel, upload year, upload
            // month, duration band and YouTube category. Categories are deduped by id; every
            // membership becomes a cross-ref. Videos with no metadata simply produce no
            // auto-categories (they surface under the "Others" tab's Uncategorized filter instead).
            val autoCategories = LinkedHashMap<String, CategoryEntity>()
            val crossRefs = mutableListOf<VideoCategoryCrossRef>()
            for (video in videos) {
                for (a in autoAssignmentsOf(video)) {
                    autoCategories.getOrPut(a.id) { CategoryEntity(a.id, a.name, a.type, now) }
                    crossRefs += VideoCategoryCrossRef(video.youtubeId, a.id)
                }
            }

            db.withTransaction {
                videoDao.upsert(videos)
                // Videos gone from the server (deleted/renamed) leave the library, and auto
                // memberships are rebuilt from scratch so stale assignments (changed channel,
                // date or duration) don't accumulate across syncs. Manual memberships survive
                // except where their video disappeared.
                videoDao.deleteNotSyncedAt(now)
                categoryDao.clearAutoCrossRefs(keepType = CATEGORY_TYPE_MANUAL)
                categoryDao.upsertAll(autoCategories.values.toList())
                if (crossRefs.isNotEmpty()) categoryDao.upsertCrossRefs(crossRefs)
                categoryDao.pruneOrphanCrossRefs()
                categoryDao.pruneEmptyCategories(CATEGORY_TYPE_MANUAL)
            }

            settings.setLastSyncAt(now)
            SyncResult.Success(
                itemCount = items.size,
                matched = videos.size,
                indexed = videos.count { it.metadataSource != METADATA_SOURCE_JELLYFIN },
                categories = autoCategories.size,
            )
        }
    }

    private data class AutoAssignment(val id: String, val name: String, val type: String)

    /** The auto-categories a single [video] belongs to, along every dimension. */
    private fun autoAssignmentsOf(video: VideoEntity): List<AutoAssignment> = buildList {
        video.channel?.takeIf { it.isNotBlank() }?.let { channel ->
            val id = "channel:" + (video.channelId?.takeIf { it.isNotBlank() } ?: channel)
            add(AutoAssignment(id, channel, CATEGORY_TYPE_AUTO_CHANNEL))
        }
        yearOf(video.uploadDate)?.let { add(AutoAssignment("year:$it", it, CATEGORY_TYPE_AUTO_YEAR)) }
        yearMonthOf(video.uploadDate)?.let { add(AutoAssignment("month:$it", it, CATEGORY_TYPE_AUTO_MONTH)) }
        DurationBucket.of(video.durationSeconds)?.let {
            add(AutoAssignment("duration:${it.id}", it.label, CATEGORY_TYPE_AUTO_DURATION))
        }
        for (raw in video.youtubeCategories) {
            val name = raw.trim()
            if (name.isNotBlank()) add(AutoAssignment("ytcat:$name", name, CATEGORY_TYPE_AUTO_YT_CATEGORY))
        }
    }

    /**
     * Fetch metadata for one video in-app with yt-dlp, overwrite its record (source = YTDLP,
     * stamped now) and re-derive its auto-categories. Same fields/converters as an index match.
     */
    suspend fun fetchMetadata(youtubeId: String): FetchResult {
        val existing = videoDao.get(youtubeId) ?: return FetchResult.Error("Video not found locally.")
        val entry = try {
            ytDlp.fetch(youtubeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchResult.Error(e.message ?: "yt-dlp failed to fetch metadata.")
        }
        applyFetched(youtubeId, entry)
        return FetchResult.Success(entry.title ?: existing.title)
    }

    /**
     * Overwrite [youtubeId]'s row with yt-dlp [entry] metadata and refresh its auto-categories.
     * Re-reads the row under [writeMutex] so a sync or watch-state write that landed since the
     * caller's snapshot is never reverted. Returns false when the video no longer exists.
     */
    private suspend fun applyFetched(youtubeId: String, entry: IndexEntry): Boolean =
        writeMutex.withLock {
            val existing = videoDao.get(youtubeId) ?: return@withLock false
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                title = entry.title ?: existing.title,
                channel = entry.channel ?: existing.channel,
                channelId = entry.channelId ?: existing.channelId,
                durationSeconds = entry.duration ?: existing.durationSeconds,
                uploadDate = entry.uploadDate ?: existing.uploadDate,
                description = entry.description ?: existing.description,
                tags = entry.tags ?: existing.tags,
                youtubeCategories = entry.categories ?: existing.youtubeCategories,
                // Strip a legacy embedded api key so it can't persist past this write.
                thumbnailUrl = entry.thumbnail ?: stripApiKey(existing.thumbnailUrl),
                metadataSource = METADATA_SOURCE_YTDLP,
                metadataUpdatedAt = now,
                lastSyncedAt = now,
            )

            // Re-derive this video's auto memberships: drop the old ones, add the new, prune orphans.
            val assignments = autoAssignmentsOf(updated)
            db.withTransaction {
                videoDao.upsert(updated)
                categoryDao.removeAutoCrossRefsForVideo(updated.youtubeId, keepType = CATEGORY_TYPE_MANUAL)
                categoryDao.upsertAll(assignments.map { CategoryEntity(it.id, it.name, it.type, now) })
                if (assignments.isNotEmpty()) {
                    categoryDao.upsertCrossRefs(assignments.map { VideoCategoryCrossRef(updated.youtubeId, it.id) })
                }
                categoryDao.pruneEmptyCategories(CATEGORY_TYPE_MANUAL)
            }
            true
        }

    /**
     * Fetch metadata for every uncategorized (Jellyfin-only) video, one at a time, publishing
     * progress via [bulkFetch]. No-op if already running.
     */
    fun startFetchMissing() {
        if (bulkJob?.isActive == true) return
        bulkJob = repoScope.launch {
            val targets = videoDao.getBySource(METADATA_SOURCE_JELLYFIN)
            if (targets.isEmpty()) {
                _bulkFetch.value = BulkFetch.Done(0, 0)
                return@launch
            }
            var failed = 0
            _bulkFetch.value = BulkFetch.Running(0, targets.size, 0)
            targets.forEachIndexed { i, video ->
                // A failure applying the result counts as failed too, so an unexpected exception
                // can't kill the process or strand the Running state.
                val ok = try {
                    applyFetched(video.youtubeId, ytDlp.fetch(video.youtubeId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!ok) failed++
                _bulkFetch.value = BulkFetch.Running(i + 1, targets.size, failed)
            }
            _bulkFetch.value = BulkFetch.Done(targets.size, failed)
        }
    }

    fun cancelFetchMissing() {
        bulkJob?.cancel()
        bulkJob = null
        _bulkFetch.value = BulkFetch.Idle
    }

    /** Clear a terminal [BulkFetch.Done] once the UI has shown it. */
    fun acknowledgeBulkFetch() {
        if (_bulkFetch.value is BulkFetch.Done) _bulkFetch.value = BulkFetch.Idle
    }

    /**
     * Wipe all locally cached library data (videos, categories, their links) and
     * reset the last-sync marker. Connection settings — server URL, API key, user,
     * folder scope — are left untouched, so a subsequent sync rebuilds from scratch.
     */
    suspend fun clearLocalData() {
        writeMutex.withLock {
            db.withTransaction {
                categoryDao.clearCrossRefs()
                categoryDao.clearCategories()
                videoDao.clear()
            }
            settings.setLastSyncAt(0L)
        }
    }

    suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean {
        val video = writeMutex.withLock {
            val v = videoDao.get(youtubeId) ?: return false
            videoDao.updateWatchState(youtubeId, played, if (played) v.playbackPositionTicks else 0L)
            localWatchWrites[youtubeId] = System.currentTimeMillis()
            v
        }
        val s = settings.snapshot()
        val itemId = video.jellyfinItemId
        if (s.isConnected && itemId != null) {
            return try {
                jellyfin.setPlayed(s.serverUrl, s.apiKey, s.userId, itemId, played)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
        return true
    }

    /**
     * Record where an external player stopped: persist the resume position locally and, on
     * Jellyfin, write the playstate directly to the user's item data. Writing UserData (rather
     * than posting /Sessions/Playing/Stopped) is what actually persists the position and lands
     * the item in "Continue Watching" — the session endpoints only commit playstate for a live,
     * progress-tracked session, which an external-player handoff can't sustain.
     *
     * When the video finished (or stopped within a few seconds of the end) it is marked played and
     * the resume position cleared, matching Jellyfin's own behaviour. Best-effort — network
     * failures are swallowed so local state still updates.
     */
    fun reportPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean) {
        // Fire-and-forget on the repository's own scope: the screen that launched the external
        // player may be gone (back press, rotation) before the local write and the up-to-30s
        // network report finish, and losing the resume position is not acceptable.
        repoScope.launch { onPlaybackStopped(youtubeId, positionMs, completed) }
    }

    private suspend fun onPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean) {
        val positionTicks = millisToTicks(positionMs)
        var finished = completed
        val video = writeMutex.withLock {
            val v = videoDao.get(youtubeId) ?: return
            finished = completed ||
                (v.durationSeconds > 0 && ticksToSeconds(positionTicks) >= v.durationSeconds - 5)
            videoDao.updateWatchState(youtubeId, finished, if (finished) 0L else positionTicks)
            localWatchWrites[youtubeId] = System.currentTimeMillis()
            v
        }

        val s = settings.snapshot()
        val itemId = video.jellyfinItemId
        if (s.isConnected && itemId != null) {
            runCatching {
                jellyfin.updatePlaybackState(
                    serverUrl = s.serverUrl,
                    apiKey = s.apiKey,
                    userId = s.userId,
                    itemId = itemId,
                    positionTicks = if (finished) 0L else positionTicks,
                    played = finished,
                    lastPlayedDate = Instant.now().toString(),
                )
            }
        }
    }

    /**
     * Creates a Jellyfin playlist named [name] from every video in [categoryId] that has a
     * Jellyfin item, ordered by file name. Works for stored categories and "Others" filters alike.
     */
    suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult {
        val s = settings.snapshot()
        if (!s.isConnected) return PlaylistResult.Error("Not connected. Configure Jellyfin in Settings.")

        val playlistName = name.trim()
        if (playlistName.isBlank()) return PlaylistResult.Error("Playlist name can't be empty.")

        val itemIds = videosForCategory(categoryId).mapNotNull { it.jellyfinItemId }
        if (itemIds.isEmpty()) return PlaylistResult.Error("No playable videos in this category.")

        return try {
            jellyfin.createPlaylist(s.serverUrl, s.apiKey, s.userId, playlistName, itemIds)
            PlaylistResult.Success(playlistName, itemIds.size)
        } catch (e: Exception) {
            PlaylistResult.Error("Failed to create playlist: ${e.message}")
        }
    }

    /** Videos in [categoryId], ordered by file name — routes the "Others" virtual filters. */
    private suspend fun videosForCategory(categoryId: String): List<VideoEntity> = when (categoryId) {
        VIRTUAL_CATEGORY_UNCATEGORIZED -> videoDao.getBySource(METADATA_SOURCE_JELLYFIN)
        VIRTUAL_CATEGORY_CONTINUE -> videoDao.getContinueWatching()
        VIRTUAL_CATEGORY_UNWATCHED -> videoDao.getUnwatched()
        VIRTUAL_CATEGORY_WATCHED -> videoDao.getWatched()
        else -> videoDao.getByCategory(categoryId)
    }
}
