package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategoryVideosViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    private val categoryId: String,
) : ViewModel() {

    /** Null while the first Room emission is pending, so the UI can tell loading from empty. */
    val videos: StateFlow<List<Video>?> = repo.observeVideosByCategory(categoryId)
        .stateIn(viewModelScope, WhileUiSubscribed, null)

    // Both bulk runs live on the repository, so their progress survives leaving this screen —
    // and outlives this ViewModel, which is scoped to one category at a time.
    val bulkFetch: StateFlow<BulkProgress> = repo.bulkFetch
    val bulkRemove: StateFlow<BulkProgress> = repo.bulkRemove

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

    fun startRemoveWatched() = repo.startRemoveWatched()
    fun cancelRemoveWatched() = repo.cancelRemoveWatched()
    fun acknowledgeBulkRemove() = repo.acknowledgeBulkRemove()
}
