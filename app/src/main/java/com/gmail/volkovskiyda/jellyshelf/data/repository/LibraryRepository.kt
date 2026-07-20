package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoCategoryCrossRef
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.util.YoutubeId
import com.gmail.volkovskiyda.jellyshelf.util.fileNameFromPath
import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
import com.gmail.volkovskiyda.jellyshelf.util.yearMonthOf
import com.gmail.volkovskiyda.jellyshelf.util.yearOf
import java.time.Instant
import kotlinx.coroutines.flow.Flow

sealed interface SyncResult {
    data class Success(val itemCount: Int, val matched: Int, val categories: Int) : SyncResult
    data class Error(val message: String) : SyncResult
}

sealed interface PlaylistResult {
    data class Success(val name: String, val count: Int) : PlaylistResult
    data class Error(val message: String) : PlaylistResult
}

class LibraryRepository(
    private val db: JellyshelfDatabase,
    private val jellyfin: JellyfinRepository,
    private val settings: SettingsRepository,
) {
    private val videoDao = db.videoDao()
    private val categoryDao = db.categoryDao()

    fun observeVideos(): Flow<List<VideoEntity>> = videoDao.observeAll()

    /**
     * Videos filtered to [bucket] (all durations when null), with those matching [query] listed
     * first and the rest after it — a soft search, so nothing is hidden. Each group stays sorted
     * by file name.
     */
    fun searchVideos(query: String, bucket: DurationBucket?): Flow<List<VideoEntity>> =
        videoDao.search(query, bucket?.minSeconds ?: 0L, bucket?.maxSeconds ?: Long.MAX_VALUE)
    fun observeVideosByCategory(categoryId: String): Flow<List<VideoEntity>> =
        videoDao.observeByCategory(categoryId)

    fun observeVideo(youtubeId: String): Flow<VideoEntity?> = videoDao.observe(youtubeId)
    fun observeCategories(): Flow<List<CategoryWithCount>> = categoryDao.observeWithCounts()
    fun searchCategories(query: String): Flow<List<CategoryWithCount>> =
        categoryDao.searchWithCounts(query)
    fun observeManualCategories(): Flow<List<CategoryEntity>> =
        categoryDao.observeByType(CATEGORY_TYPE_MANUAL)

    fun observeCategoriesForVideo(youtubeId: String): Flow<List<CategoryEntity>> =
        categoryDao.observeForVideo(youtubeId)

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
        val videos = mutableListOf<VideoEntity>()

        for (item in items) {
            val youtubeId = YoutubeId.fromPath(item.path) ?: continue
            val meta = index[youtubeId]
            val duration = meta?.duration
                ?: item.runTimeTicks?.let { ticksToSeconds(it) }
                ?: 0L
            val thumb = meta?.thumbnail
                ?: "$serverBase/Items/${item.id}/Images/Primary?maxWidth=480&api_key=${s.apiKey}"

            videos += VideoEntity(
                youtubeId = youtubeId,
                jellyfinItemId = item.id,
                fileName = fileNameFromPath(item.path) ?: (meta?.title ?: item.name ?: youtubeId),
                title = meta?.title ?: item.name ?: youtubeId,
                channel = meta?.channel,
                channelId = meta?.channelId,
                durationSeconds = duration,
                uploadDate = meta?.uploadDate ?: item.productionYear?.toString(),
                description = meta?.description ?: item.overview,
                tags = meta?.tags ?: item.tags ?: emptyList(),
                youtubeCategories = meta?.categories ?: item.genres ?: emptyList(),
                thumbnailUrl = thumb,
                played = item.userData?.played ?: false,
                playbackPositionTicks = item.userData?.playbackPositionTicks ?: 0L,
                playCount = item.userData?.playCount ?: 0,
                lastSyncedAt = now,
            )
        }

        videoDao.upsert(videos)

        // Auto-categorize each video along several dimensions: channel, upload year, upload
        // month, duration band and YouTube category. Categories are deduped by id; every
        // membership becomes a cross-ref.
        val autoCategories = LinkedHashMap<String, CategoryEntity>()
        val crossRefs = mutableListOf<VideoCategoryCrossRef>()

        fun assign(youtubeId: String, id: String, name: String, type: String) {
            autoCategories.getOrPut(id) { CategoryEntity(id = id, name = name, type = type, createdAt = now) }
            crossRefs += VideoCategoryCrossRef(youtubeId, id)
        }

        for (video in videos) {
            video.channel?.takeIf { it.isNotBlank() }?.let { channel ->
                val id = "channel:" + (video.channelId?.takeIf { it.isNotBlank() } ?: channel)
                assign(video.youtubeId, id, channel, CATEGORY_TYPE_AUTO_CHANNEL)
            }
            yearOf(video.uploadDate)?.let { year ->
                assign(video.youtubeId, "year:$year", year, CATEGORY_TYPE_AUTO_YEAR)
            }
            yearMonthOf(video.uploadDate)?.let { month ->
                assign(video.youtubeId, "month:$month", month, CATEGORY_TYPE_AUTO_MONTH)
            }
            DurationBucket.of(video.durationSeconds)?.let { bucket ->
                assign(video.youtubeId, "duration:${bucket.id}", bucket.label, CATEGORY_TYPE_AUTO_DURATION)
            }
            for (raw in video.youtubeCategories) {
                val name = raw.trim()
                if (name.isNotBlank()) assign(video.youtubeId, "ytcat:$name", name, CATEGORY_TYPE_AUTO_YT_CATEGORY)
            }
        }

        for (category in autoCategories.values) categoryDao.upsert(category)
        if (crossRefs.isNotEmpty()) categoryDao.upsertCrossRefs(crossRefs)

        settings.setLastSyncAt(now)
        return SyncResult.Success(
            itemCount = items.size,
            matched = videos.size,
            categories = autoCategories.size,
        )
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

    suspend fun createManualCategory(name: String): String {
        val id = "manual:" + name.trim().lowercase().replace(Regex("\\s+"), "-")
        categoryDao.upsert(
            CategoryEntity(
                id = id,
                name = name.trim(),
                type = CATEGORY_TYPE_MANUAL,
                createdAt = System.currentTimeMillis(),
            )
        )
        return id
    }

    suspend fun setVideoInCategory(youtubeId: String, categoryId: String, inCategory: Boolean) {
        if (inCategory) categoryDao.upsertCrossRef(VideoCategoryCrossRef(youtubeId, categoryId))
        else categoryDao.removeCrossRef(youtubeId, categoryId)
    }

    /**
     * Creates a Jellyfin playlist named [name] from every video in [categoryId] that has a
     * Jellyfin item, ordered by file name.
     */
    suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult {
        val s = settings.snapshot()
        if (!s.isConnected) return PlaylistResult.Error("Not connected. Configure Jellyfin in Settings.")

        val playlistName = name.trim()
        if (playlistName.isBlank()) return PlaylistResult.Error("Playlist name can't be empty.")

        // getByCategory already orders by fileName; keep that order for the playlist.
        val itemIds = videoDao.getByCategory(categoryId).mapNotNull { it.jellyfinItemId }
        if (itemIds.isEmpty()) return PlaylistResult.Error("No playable videos in this category.")

        return try {
            jellyfin.createPlaylist(s.serverUrl, s.apiKey, s.userId, playlistName, itemIds)
            PlaylistResult.Success(playlistName, itemIds.size)
        } catch (e: Exception) {
            PlaylistResult.Error("Failed to create playlist: ${e.message}")
        }
    }
}
