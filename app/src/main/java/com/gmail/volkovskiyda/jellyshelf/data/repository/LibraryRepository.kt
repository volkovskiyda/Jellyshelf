package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoCategoryCrossRef
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.util.YoutubeId
import com.gmail.volkovskiyda.jellyshelf.util.fileNameFromPath
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
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
    fun searchVideos(query: String): Flow<List<VideoEntity>> = videoDao.search(query)
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
                thumbnailUrl = thumb,
                played = item.userData?.played ?: false,
                playbackPositionTicks = item.userData?.playbackPositionTicks ?: 0L,
                playCount = item.userData?.playCount ?: 0,
                lastSyncedAt = now,
            )
        }

        videoDao.upsert(videos)

        // Auto-categorize by channel.
        val autoCategories = mutableSetOf<String>()
        val crossRefs = mutableListOf<VideoCategoryCrossRef>()
        for (video in videos) {
            val channel = video.channel?.takeIf { it.isNotBlank() } ?: continue
            val categoryId = "channel:" + (video.channelId?.takeIf { it.isNotBlank() } ?: channel)
            if (autoCategories.add(categoryId)) {
                categoryDao.upsert(
                    CategoryEntity(
                        id = categoryId,
                        name = channel,
                        type = CATEGORY_TYPE_AUTO_CHANNEL,
                        createdAt = now,
                    )
                )
            }
            crossRefs += VideoCategoryCrossRef(video.youtubeId, categoryId)
        }
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
