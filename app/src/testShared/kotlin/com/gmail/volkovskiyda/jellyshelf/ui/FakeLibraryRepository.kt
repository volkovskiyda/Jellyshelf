package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * A [LibraryRepository] covering the **browse reads** only: the video list, the search/filter
 * query and the count. Everything else — syncing, metadata fetches, playlists, watch-state writes
 * — throws, deliberately: a test that reaches one of them has wandered outside what this fake
 * models, and a silent no-op would let it pass while proving nothing.
 *
 * The two exceptions are [seedDemoLibrary] and [clearLocalData], which are *counted* rather than
 * either performed or refused: whether they were called, and how often, is the assertion demo-mode
 * tests are making, and a throw would only tell them the call happened once.
 *
 * [videos] and [searchResults] are hot, so a test can emit a new list into a collector that is
 * already running and watch the flow assembly react.
 */
@Suppress("TooManyFunctions") // mirrors the interface it fakes
class FakeLibraryRepository(
    initial: List<Video> = emptyList(),
) : LibraryRepository {

    /** What the unfiltered browse flow emits. */
    val videos = MutableStateFlow(initial)

    /** What the search/filter flow emits, whatever the terms. */
    val searchResults = MutableStateFlow(initial)

    /** Every (query, bucket) pair [searchVideos] has been asked for, in order. */
    val searches = mutableListOf<Pair<String, DurationBucket?>>()

    override fun observeVideos(): Flow<List<Video>> = videos

    override fun searchVideos(query: String, bucket: DurationBucket?): Flow<List<Video>> {
        searches += query to bucket
        return searchResults
    }

    override fun videoCount(): Flow<Int> = videos.map { it.size }

    override fun observeVideosByCategory(categoryId: String): Flow<List<Video>> = videos

    override fun observeVideo(youtubeId: String): Flow<Video?> =
        videos.map { list -> list.firstOrNull { it.youtubeId == youtubeId } }

    override fun observeCategories(): Flow<List<CategoryWithCount>> = notModelled()

    override fun searchCategories(query: String): Flow<List<CategoryWithCount>> = notModelled()

    override fun observeOthers(): Flow<List<CategoryWithCount>> = notModelled()

    override val bulkFetch: StateFlow<BulkProgress> = MutableStateFlow(BulkProgress.Idle)
    override val bulkRemove: StateFlow<BulkProgress> = MutableStateFlow(BulkProgress.Idle)

    override suspend fun sync(): SyncResult = notModelled()
    override suspend fun fetchMetadata(youtubeId: String): FetchResult = notModelled()
    override fun startFetchMissing(): Unit = notModelled()
    override fun cancelFetchMissing(): Unit = notModelled()
    override fun acknowledgeBulkFetch(): Unit = notModelled()
    override fun startRemoveWatched(): Unit = notModelled()
    override fun cancelRemoveWatched(): Unit = notModelled()
    override fun acknowledgeBulkRemove(): Unit = notModelled()

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
    }
    override suspend fun removeVideo(youtubeId: String): Unit = notModelled()
    override suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean = notModelled()
    override fun reportPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean): Unit =
        notModelled()

    override fun savePlaybackPosition(youtubeId: String, positionMs: Long): Unit = notModelled()
    override suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult =
        notModelled()

    private fun notModelled(): Nothing =
        error("FakeLibraryRepository models the browse reads only")
}
