package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
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
    fun searchVideos(query: String): Flow<List<Video>>
    fun observeVideosByCategory(categoryId: String): Flow<List<Video>>
    fun observeVideo(youtubeId: String): Flow<Video?>

    /**
     * A batch of videos by id, read once rather than observed — for the playback queue, which
     * needs every row it is about to play and needs none of them to keep updating.
     *
     * Keyed by `youtubeId`, and **an id with no row is absent from the map** rather than mapped to
     * null: callers walk their own id list and decide what a miss means. Nothing here is a `Flow`
     * on purpose; the one existing per-id read is, and collecting one value from hundreds of them
     * is what this replaces.
     */
    suspend fun videosByIds(youtubeIds: List<String>): Map<String, Video>
    fun observeCategories(): Flow<List<CategoryWithCount>>

    /** The categories one video belongs to — every stored type; callers pick the dimensions they show. */
    fun observeCategoriesForVideo(youtubeId: String): Flow<List<Category>>
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

    /**
     * The multi-selection run in flight, or null when there is none. One runner for all four
     * [SelectionAction]s and for both video lists: only one selection run can be going at a time,
     * so a single header describes it and a single Cancel stops it, wherever the user walks to
     * while it works.
     */
    val selectionRun: StateFlow<SelectionRun?>

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
     * Runs [action] over the videos the user selected, reporting through [selectionRun]. No-op
     * while a selection run is already in flight.
     *
     * Ids rather than videos, because the selection outlives the emission it was made from: rows
     * that have gone by the time the run starts are simply not among its targets, and the run's
     * total counts what was actually found.
     *
     * [SelectionAction.REMOVE] deletes on the Jellyfin server, media file included, and only drops
     * the local row once the server has confirmed it — except for a video the server no longer has
     * (never matched to an item, or already dropped from its listings), which has nothing to
     * delete there and is removed locally instead. That is the one destructive action here; the
     * other three are recoverable by acting again.
     */
    fun startSelectionAction(action: SelectionAction, youtubeIds: List<String>)
    fun cancelSelectionAction()
    fun acknowledgeSelectionRun()

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

    /**
     * Creates a Jellyfin playlist named [name] from the selected videos, in the order the list they
     * were picked from was showing. Videos the server has no item for are left out; a selection
     * with none at all is an error rather than an empty playlist.
     */
    suspend fun createPlaylistFromVideos(youtubeIds: List<String>, name: String): PlaylistResult
}
