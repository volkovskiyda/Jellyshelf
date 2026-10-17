package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_MISSING
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_NOW_PLAYING
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_SKIPPED
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_WATCHED
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingQueue
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingState
import com.gmail.volkovskiyda.jellyshelf.playback.upcoming
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.selection.VideoSelectionController
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryVideosViewModel(
    private val repo: LibraryRepository,
    settingsState: AppSettingsState,
    nowPlayingQueue: NowPlayingQueue,
    nowPlayingState: NowPlayingState,
    private val categoryId: String,
) : ViewModel() {

    /**
     * The Now playing filter: the session's queue rather than a library query, every row marked
     * playing or skipped, and a row tap that jumps playback instead of queuing the list. Fixed for
     * the ViewModel's lifetime, like [removeKind].
     */
    val isNowPlayingFilter: Boolean = categoryId == VIRTUAL_CATEGORY_NOW_PLAYING

    private val playingIdFlow = nowPlayingState.nowPlaying.map { it?.youtubeId }.distinctUntilChanged()

    /** Null while the first Room emission is pending, so the UI can tell loading from empty. */
    val videos: StateFlow<List<Video>?> = if (isNowPlayingFilter) {
        // The rows come from Room, for the watch markers the other filters show, but the members
        // and their order are the session's: the playing video and what follows it (see upcoming).
        combine(nowPlayingQueue.items, playingIdFlow) { items, playingId ->
            upcoming(items, playingId).map { it.mediaId }
        }
            .distinctUntilChanged()
            .flatMapLatest(repo::observeVideosInOrder)
    } else {
        repo.observeVideosByCategory(categoryId)
    }
        .stateIn(viewModelScope, WhileUiSubscribed, null)

    /** The video playing now, for the Now playing filter's marker; null when nothing is. */
    val playingId: StateFlow<String?> = playingIdFlow
        .stateIn(viewModelScope, WhileUiSubscribed, null)

    /** The skip list, for the Now playing filter's skipped rows. */
    val skippedIds: StateFlow<Set<String>> = repo.observeSkippedIds()
        .stateIn(viewModelScope, WhileUiSubscribed, emptySet())

    /**
     * Multi-select over this category's videos. Scoped to this ViewModel, which is scoped to the
     * navigation entry — so walking out to a video's detail screen and back keeps the selection,
     * and leaving the category drops it.
     */
    val selection = VideoSelectionController(repo, viewModelScope)

    /**
     * Only the removal confirmation reads this, to stop promising a server delete an install with
     * no server cannot perform. The action itself needs no branch — the repository simulates the
     * deletes, and they drop the rows either way.
     */
    val demoMode: StateFlow<Boolean> = settingsState.settings
        .map { it?.demoMode == true }
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    /**
     * The Skipped filter: every row carries an unskip button, and its selection can be unskipped
     * as a whole. Fixed for the ViewModel's lifetime, like [removeKind].
     */
    val isSkippedFilter: Boolean = categoryId == VIRTUAL_CATEGORY_SKIPPED

    fun unskip(youtubeId: String) {
        viewModelScope.launch { repo.setSkipped(listOf(youtubeId), skipped = false) }
    }

    /**
     * Which bulk removal this category offers, if any. Fixed for the lifetime of the ViewModel —
     * it is scoped to one category id — so everything below routes off it rather than off the id.
     */
    internal val removeKind: RemoveKind? = when (categoryId) {
        VIRTUAL_CATEGORY_WATCHED -> RemoveKind.WATCHED
        VIRTUAL_CATEGORY_MISSING -> RemoveKind.MISSING
        else -> null
    }

    // Both bulk runs live on the repository, so their progress survives leaving this screen —
    // and outlives this ViewModel, which is scoped to one category at a time.
    val bulkFetch: StateFlow<BulkProgress> = repo.bulkFetch

    /**
     * The removal runner this category's header renders. The two are separate on the repository
     * because they mean opposite things, and a run started on one filter must not appear as
     * progress on the other; a category that offers no removal renders no header, and gets a
     * constant Idle rather than an arbitrary one of the two.
     */
    val bulkRemove: StateFlow<BulkProgress> = when (removeKind) {
        RemoveKind.WATCHED -> repo.bulkRemove
        RemoveKind.MISSING -> repo.bulkRemoveMissing
        null -> MutableStateFlow(BulkProgress.Idle)
    }

    fun startFetchMissing() = repo.startFetchMissing()
    fun cancelFetchMissing() = repo.cancelFetchMissing()
    fun acknowledgeBulkFetch() = repo.acknowledgeBulkFetch()

    fun startRemove() = when (removeKind) {
        RemoveKind.WATCHED -> repo.startRemoveWatched()
        RemoveKind.MISSING -> repo.startRemoveMissing()
        null -> Unit
    }

    fun cancelRemove() = when (removeKind) {
        RemoveKind.WATCHED -> repo.cancelRemoveWatched()
        RemoveKind.MISSING -> repo.cancelRemoveMissing()
        null -> Unit
    }

    fun acknowledgeBulkRemove() = when (removeKind) {
        RemoveKind.WATCHED -> repo.acknowledgeBulkRemove()
        RemoveKind.MISSING -> repo.acknowledgeBulkRemoveMissing()
        null -> Unit
    }
}
