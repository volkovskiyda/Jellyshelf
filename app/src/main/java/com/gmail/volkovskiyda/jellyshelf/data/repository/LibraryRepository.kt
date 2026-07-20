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
import com.gmail.volkovskiyda.jellyshelf.data.local.METADATA_SOURCE_INDEX
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
import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.util.YoutubeId
import com.gmail.volkovskiyda.jellyshelf.util.fileNameFromPath
import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
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

    // Long-running bulk fetch runs here so it outlives the screen that started it.
    private val bulkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        videoDao.search(query, bucket?.minSeconds ?: 0L, bucket?.maxSeconds ?: Long.MAX_VALUE)

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
        categoryDao.searchWithCounts(query)

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

        val items = try {
            jellyfin.fetchAllItems(s.serverUrl, s.apiKey, s.userId, s.libraryId)
        } catch (e: Exception) {
            return SyncResult.Error("Failed to load library: ${e.message}")
        }

        val index: Map<String, IndexEntry> = if (s.indexUrl.isNotBlank()) {
            try {
                jellyfin.fetchIndex(s.indexUrl).associateBy { it.id }
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val now = System.currentTimeMillis()
        val serverBase = s.serverUrl.trim().removeSuffix("/")
        // Existing rows, to honour newest-wins: a manual in-app yt-dlp fetch is kept over an index
        // entry unless the index entry is genuinely newer.
        val existingById = videoDao.getAll().associateBy { it.youtubeId }
        val videos = mutableListOf<VideoEntity>()

        for (item in items) {
            val youtubeId = YoutubeId.fromPath(item.path) ?: continue
            val existing = existingById[youtubeId]
            val meta = index[youtubeId]
            val indexUpdatedAt = meta?.fetchedAt?.let { it * 1000 } // epoch seconds -> millis

            val played = item.userData?.played ?: false
            val positionTicks = item.userData?.playbackPositionTicks ?: 0L
            val playCount = item.userData?.playCount ?: 0

            // Keep a prior in-app yt-dlp fetch unless the index now carries a newer extraction.
            val keepYtdlp = existing?.metadataSource == METADATA_SOURCE_YTDLP &&
                (meta == null || indexUpdatedAt == null || indexUpdatedAt <= existing.metadataUpdatedAt)

            val fileName = fileNameFromPath(item.path)
                ?: (meta?.title ?: existing?.title ?: item.name ?: youtubeId)

            videos += if (keepYtdlp) {
                // Preserve the yt-dlp metadata; refresh only Jellyfin-owned fields (watch state, item id).
                existing!!.copy(
                    jellyfinItemId = item.id,
                    fileName = fileName,
                    played = played,
                    playbackPositionTicks = positionTicks,
                    playCount = playCount,
                    lastSyncedAt = now,
                )
            } else {
                val source = if (meta != null) METADATA_SOURCE_INDEX else METADATA_SOURCE_JELLYFIN
                val metadataUpdatedAt = if (meta != null) (indexUpdatedAt ?: now) else 0L
                val thumb = meta?.thumbnail
                    ?: "$serverBase/Items/${item.id}/Images/Primary?maxWidth=480&api_key=${s.apiKey}"
                VideoEntity(
                    youtubeId = youtubeId,
                    jellyfinItemId = item.id,
                    fileName = fileName,
                    title = meta?.title ?: item.name ?: youtubeId,
                    channel = meta?.channel,
                    channelId = meta?.channelId,
                    durationSeconds = meta?.duration
                        ?: item.runTimeTicks?.let { ticksToSeconds(it) }
                        ?: 0L,
                    uploadDate = meta?.uploadDate ?: item.productionYear?.toString(),
                    description = meta?.description ?: item.overview,
                    tags = meta?.tags ?: item.tags ?: emptyList(),
                    youtubeCategories = meta?.categories ?: item.genres ?: emptyList(),
                    thumbnailUrl = thumb,
                    played = played,
                    playbackPositionTicks = positionTicks,
                    playCount = playCount,
                    lastSyncedAt = now,
                    metadataSource = source,
                    metadataUpdatedAt = metadataUpdatedAt,
                )
            }
        }

        videoDao.upsert(videos)

        // Auto-categorize each video along several dimensions: channel, upload year, upload month,
        // duration band and YouTube category. Categories are deduped by id; every membership becomes
        // a cross-ref. Videos with no metadata simply produce no auto-categories (they surface under
        // the "Others" tab's Uncategorized filter instead).
        val autoCategories = LinkedHashMap<String, CategoryEntity>()
        val crossRefs = mutableListOf<VideoCategoryCrossRef>()
        for (video in videos) {
            for (a in autoAssignmentsOf(video)) {
                autoCategories.getOrPut(a.id) { CategoryEntity(a.id, a.name, a.type, now) }
                crossRefs += VideoCategoryCrossRef(video.youtubeId, a.id)
            }
        }

        for (category in autoCategories.values) categoryDao.upsert(category)
        if (crossRefs.isNotEmpty()) categoryDao.upsertCrossRefs(crossRefs)
        categoryDao.pruneEmptyCategories(CATEGORY_TYPE_MANUAL)

        settings.setLastSyncAt(now)
        return SyncResult.Success(
            itemCount = items.size,
            matched = videos.size,
            indexed = videos.count { it.metadataSource != METADATA_SOURCE_JELLYFIN },
            categories = autoCategories.size,
        )
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
        applyFetched(existing, entry)
        return FetchResult.Success(entry.title ?: existing.title)
    }

    /** Overwrite [existing] with yt-dlp [entry] metadata and refresh its auto-categories. */
    private suspend fun applyFetched(existing: VideoEntity, entry: IndexEntry) {
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
            thumbnailUrl = entry.thumbnail ?: existing.thumbnailUrl,
            metadataSource = METADATA_SOURCE_YTDLP,
            metadataUpdatedAt = now,
            lastSyncedAt = now,
        )
        videoDao.upsert(updated)

        // Re-derive this video's auto memberships: drop the old ones, add the new, prune orphans.
        categoryDao.removeAutoCrossRefsForVideo(updated.youtubeId, keepType = CATEGORY_TYPE_MANUAL)
        val assignments = autoAssignmentsOf(updated)
        for (a in assignments) categoryDao.upsert(CategoryEntity(a.id, a.name, a.type, now))
        if (assignments.isNotEmpty()) {
            categoryDao.upsertCrossRefs(assignments.map { VideoCategoryCrossRef(updated.youtubeId, it.id) })
        }
        categoryDao.pruneEmptyCategories(CATEGORY_TYPE_MANUAL)
    }

    /**
     * Fetch metadata for every uncategorized (Jellyfin-only) video, one at a time, publishing
     * progress via [bulkFetch]. No-op if already running.
     */
    fun startFetchMissing() {
        if (bulkJob?.isActive == true) return
        bulkJob = bulkScope.launch {
            val targets = videoDao.getBySource(METADATA_SOURCE_JELLYFIN)
            if (targets.isEmpty()) {
                _bulkFetch.value = BulkFetch.Done(0, 0)
                return@launch
            }
            var failed = 0
            _bulkFetch.value = BulkFetch.Running(0, targets.size, 0)
            targets.forEachIndexed { i, video ->
                val entry = try {
                    ytDlp.fetch(video.youtubeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (entry != null) applyFetched(video, entry) else failed++
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
        categoryDao.clearCrossRefs()
        categoryDao.clearCategories()
        videoDao.clear()
        settings.setLastSyncAt(0L)
    }

    suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean {
        val video = videoDao.get(youtubeId) ?: return false
        val position = if (played) video.playbackPositionTicks else 0L
        videoDao.updateWatchState(youtubeId, played, position)
        val s = settings.snapshot()
        val itemId = video.jellyfinItemId
        if (s.isConnected && itemId != null) {
            return try {
                jellyfin.setPlayed(s.serverUrl, s.apiKey, s.userId, itemId, played)
                true
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
    suspend fun onPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean) {
        val video = videoDao.get(youtubeId) ?: return
        val positionTicks = millisToTicks(positionMs)
        val finished = completed ||
            (video.durationSeconds > 0 && ticksToSeconds(positionTicks) >= video.durationSeconds - 5)

        videoDao.updateWatchState(youtubeId, finished, if (finished) 0L else positionTicks)

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
