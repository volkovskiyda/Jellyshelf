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
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
import kotlinx.coroutines.flow.Flow

sealed interface SyncResult {
    data class Success(val itemCount: Int, val matched: Int, val categories: Int) : SyncResult
    data class Error(val message: String) : SyncResult
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
}
