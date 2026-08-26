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
 *
 * Watch-state writes are [PlaystateRepository]'s half of the contract; extending it keeps the
 * app on one injected repository type while the members live with their own delegate.
 */
@Suppress("TooManyFunctions") // the app's single library-domain facade
interface LibraryRepository : PlaystateRepository {
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

    /**
     * Progress of the local-only removal offered on the "Missing from server" filter. A separate
     * runner from [bulkRemove] because the two mean opposite things — that one deletes media on
     * the server, this one only drops rows the server has already stopped listing — and either can
     * be left running while the user walks to the other filter.
     */
    val bulkRemoveMissing: StateFlow<BulkProgress>

    suspend fun sync(): SyncResult
    suspend fun fetchMetadata(youtubeId: String): FetchResult
    fun startFetchMissing()
    fun cancelFetchMissing()
    fun acknowledgeBulkFetch()
    fun startRemoveWatched()
    fun cancelRemoveWatched()
    fun acknowledgeBulkRemove()

    /**
     * Drops every video the server has stopped listing from the local library, without waiting out
     * the missed-sync grace period. Local only: nothing is deleted on the server, and a video that
     * turns out to still be there comes back on the next sync.
     */
    fun startRemoveMissing()
    fun cancelRemoveMissing()
    fun acknowledgeBulkRemoveMissing()

    /**
     * Fills the library with the bundled demo dataset — no server, no network — and records that
     * the library is now a demo ([com.gmail.volkovskiyda.jellyshelf.domain.model.Settings.demoMode]).
     * Auto-categories are derived by the same code a real sync uses, so the Categories screen looks
     * exactly as it would after one.
     *
     * Idempotent: rows are keyed by their id, so seeding over an existing demo library replaces it
     * rather than duplicating it. [clearLocalData] is the way out.
     */
    suspend fun seedDemoLibrary()

    suspend fun clearLocalData()
    suspend fun removeVideo(youtubeId: String)
    suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult
}
