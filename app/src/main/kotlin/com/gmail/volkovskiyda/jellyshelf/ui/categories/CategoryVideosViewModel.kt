package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_MISSING
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_WATCHED
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.selection.VideoSelectionController
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoryVideosViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    settingsState: AppSettingsState,
    private val categoryId: String,
) : ViewModel() {

    /** Null while the first Room emission is pending, so the UI can tell loading from empty. */
    val videos: StateFlow<List<Video>?> = repo.observeVideosByCategory(categoryId)
        .stateIn(viewModelScope, WhileUiSubscribed, null)

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

    private val _creatingPlaylist = MutableStateFlow(false)
    val creatingPlaylist: StateFlow<Boolean> = _creatingPlaylist.asStateFlow()

    /** One-shot outcome message for playlist creation; consume after showing. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Creates the playlist. The repository call runs non-cancellable so neither rotation nor
     * popping the screen aborts it mid-flight; the creating flag is cleared afterwards so the
     * dialog can never get stuck if something outside the repository's own handling throws.
     */
    fun createPlaylist(name: String) {
        if (_creatingPlaylist.value) return
        _creatingPlaylist.value = true
        viewModelScope.launch {
            runCatchingCancellable {
                val result = withContext(NonCancellable) {
                    repo.createPlaylistFromCategory(categoryId, name)
                }
                val resources = app.resources
                _message.value = when (result) {
                    is PlaylistResult.Success -> resources.getString(
                        R.string.playlist_created,
                        result.name,
                        resources.getQuantityString(R.plurals.video_count, result.count, result.count),
                    )
                    is PlaylistResult.Error -> result.message
                }
            }.onFailure { e ->
                _message.value = e.message ?: e.javaClass.simpleName
            }
            _creatingPlaylist.value = false
        }
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
