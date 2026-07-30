package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The library's read/write contract in domain terms. Its single implementation
 * (`data.repository.DefaultLibraryRepository`) orchestrates Room, Jellyfin and yt-dlp and maps
 * their rows/DTOs to the [Video]/[CategoryWithCount] domain models exposed here.
 */
@Suppress("TooManyFunctions") // the app's single library-domain facade
interface LibraryRepository {
    fun observeVideos(): Flow<List<Video>>
    fun searchVideos(query: String, bucket: DurationBucket?): Flow<List<Video>>
    fun observeVideosByCategory(categoryId: String): Flow<List<Video>>
    fun observeVideo(youtubeId: String): Flow<Video?>
    fun observeCategories(): Flow<List<CategoryWithCount>>
    fun searchCategories(query: String): Flow<List<CategoryWithCount>>
    fun observeOthers(): Flow<List<CategoryWithCount>>
    fun videoCount(): Flow<Int>

    val bulkFetch: StateFlow<BulkProgress>
    val bulkRemove: StateFlow<BulkProgress>

    suspend fun sync(): SyncResult
    suspend fun fetchMetadata(youtubeId: String): FetchResult
    fun startFetchMissing()
    fun cancelFetchMissing()
    fun acknowledgeBulkFetch()
    fun startRemoveWatched()
    fun cancelRemoveWatched()
    fun acknowledgeBulkRemove()
    suspend fun clearLocalData()
    suspend fun removeVideo(youtubeId: String)
    suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean
    fun reportPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean)

    /**
     * Local-only periodic position save from the in-app player, so process death mid-playback
     * can't lose the spot. No server write — the server still sees exactly one report per stop
     * ([reportPlaybackStopped]) — and never unmarks a played video. Fire-and-forget.
     */
    fun savePlaybackPosition(youtubeId: String, positionMs: Long)
    suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult
}
