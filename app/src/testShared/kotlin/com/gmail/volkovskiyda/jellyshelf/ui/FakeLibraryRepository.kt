package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlayMethod
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncPhase
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A [LibraryRepository] covering the **browse reads** only: the video list, the search query and
 * the count. Everything else — syncing, metadata fetches, playlists, watch-state writes
 * — throws, deliberately: a test that reaches one of them has wandered outside what this fake
 * models, and a silent no-op would let it pass while proving nothing.
 *
 * The exceptions are [seedDemoLibrary], [clearLocalData] and [sync], which are *counted* rather
 * than either performed or refused: whether they were called, and how often, is the assertion
 * demo-mode tests are making, and a throw would only tell them the call happened once.
 *
 * [videos] and [searchResults] are hot, so a test can emit a new list into a collector that is
 * already running and watch the flow assembly react.
 */
@Suppress("TooManyFunctions") // mirrors the interface it fakes
class FakeLibraryRepository(
    initial: List<Video> = emptyList(),
    /**
     * Runs inside [clearLocalData], for tests that need the real repository's side contract —
     * zeroing the persisted sync marker and the demo flag — to hold across the fake boundary.
     */
    private val onClear: suspend () -> Unit = {},
) : LibraryRepository {

    /** What the unfiltered browse flow emits. */
    val videos = MutableStateFlow(initial)

    /** What the search/filter flow emits, whatever the terms. */
    val searchResults = MutableStateFlow(initial)

    /** Every query [searchVideos] has been asked for, in order. */
    val searches = mutableListOf<String>()

    override fun observeVideos(): Flow<List<Video>> = videos

    override fun searchVideos(query: String): Flow<List<Video>> {
        searches += query
        return searchResults
    }

    override fun videoCount(): Flow<Int> = videos.map { it.size }

    override fun observeVideosByCategory(categoryId: String): Flow<List<Video>> = videos

    override fun observeVideo(youtubeId: String): Flow<Video?> =
        videos.map { list -> list.firstOrNull { it.youtubeId == youtubeId } }

    /** Absent ids are simply missing from the map, exactly as the real batch query leaves them. */
    override suspend fun videosByIds(youtubeIds: List<String>): Map<String, Video> {
        val wanted = youtubeIds.toSet()
        return videos.first().filter { it.youtubeId in wanted }.associateBy { it.youtubeId }
    }

    override fun observeCategories(): Flow<List<CategoryWithCount>> = notModelled()

    override fun observeCategoriesForVideo(youtubeId: String): Flow<List<Category>> = notModelled()

    override fun searchCategories(query: String): Flow<List<CategoryWithCount>> = notModelled()

    override fun observeOthers(): Flow<List<CategoryWithCount>> = notModelled()

    override val bulkFetch: StateFlow<BulkProgress> = MutableStateFlow(BulkProgress.Idle)
    override val bulkRemove: StateFlow<BulkProgress> = MutableStateFlow(BulkProgress.Idle)
    override val bulkRemoveMissing: StateFlow<BulkProgress> = MutableStateFlow(BulkProgress.Idle)

    /**
     * The multi-selection run, modelled rather than refused: a test drives it directly to watch
     * what [com.gmail.volkovskiyda.jellyshelf.ui.selection.VideoSelectionController] does as a run
     * reaches each stage, which is the whole of that class's behaviour.
     */
    val selectionRuns = MutableStateFlow<SelectionRun?>(null)
    override val selectionRun: StateFlow<SelectionRun?> = selectionRuns

    /** The phase a test wants an in-flight sync to report; drives the settings status line. */
    val syncPhases = MutableStateFlow<SyncPhase?>(null)
    override val syncPhase: StateFlow<SyncPhase?> = syncPhases

    /**
     * What [sync] returns, and how often it was called. Modelled rather than refused, unlike the
     * rest: a demo sync doesn't go through WorkManager, so the Settings ViewModel calls this
     * directly and reports what it returns.
     */
    var syncResult: SyncResult = SyncResult.Success(itemCount = 0, matched = 0, indexed = 0, categories = 0)
    var syncs = 0
        private set

    /** When set, [sync] parks on it after counting — lets a test observe the in-flight state. */
    var syncGate: CompletableDeferred<Unit>? = null

    override suspend fun sync(): SyncResult {
        syncs++
        writeOrder += "sync"
        syncGate?.await()
        return syncResult
    }

    override suspend fun fetchMetadata(youtubeId: String): FetchResult = notModelled()
    override fun startFetchMissing(): Unit = notModelled()
    override fun cancelFetchMissing(): Unit = notModelled()
    override fun acknowledgeBulkFetch(): Unit = notModelled()
    override fun startRemoveWatched(): Unit = notModelled()
    override fun cancelRemoveWatched(): Unit = notModelled()
    override fun acknowledgeBulkRemove(): Unit = notModelled()
    override fun startRemoveMissing(): Unit = notModelled()
    override fun cancelRemoveMissing(): Unit = notModelled()
    override fun acknowledgeBulkRemoveMissing(): Unit = notModelled()
    override fun cancelSelectionAction(): Unit = notModelled()
    override fun acknowledgeSelectionRun(): Unit = notModelled()

    /** Every selection action started, with the ids it was handed. Counted, not performed. */
    val selectionStarts = mutableListOf<Pair<SelectionAction, List<String>>>()

    override fun startSelectionAction(action: SelectionAction, youtubeIds: List<String>) {
        selectionStarts += action to youtubeIds
    }

    /** How many times the demo library was seeded. */
    var seeds = 0
        private set

    /** How many times the local library was wiped. */
    var clears = 0
        private set

    /** Calls to [seedDemoLibrary] and [clearLocalData] in order, for asserting which came first. */
    val writeOrder = mutableListOf<String>()

    override suspend fun seedDemoLibrary() {
        seeds++
        writeOrder += "seed"
    }

    override suspend fun clearLocalData() {
        clears++
        writeOrder += "clear"
        onClear()
    }
    override suspend fun removeVideo(youtubeId: String): Unit = notModelled()
    override suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean = notModelled()
    override fun reportPlaybackStopped(
        youtubeId: String,
        positionMs: Long,
        completed: Boolean,
        playSessionId: String?,
    ): Unit = notModelled()

    override fun reportPlaybackStarted(
        youtubeId: String,
        positionMs: Long,
        playSessionId: String?,
        playMethod: PlayMethod,
    ): Unit = notModelled()

    override fun reportPlaybackProgress(
        youtubeId: String,
        positionMs: Long,
        isPaused: Boolean,
        playSessionId: String?,
        playMethod: PlayMethod,
    ): Unit = notModelled()

    override fun savePlaybackPosition(youtubeId: String, positionMs: Long): Unit = notModelled()
    override suspend fun createPlaylistFromVideos(youtubeIds: List<String>, name: String): PlaylistResult =
        notModelled()

    private fun notModelled(): Nothing =
        error("FakeLibraryRepository models the browse reads only")
}
