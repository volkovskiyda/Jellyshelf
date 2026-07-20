package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.repository.BulkFetch
import com.gmail.volkovskiyda.jellyshelf.data.repository.PlaylistResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoryVideosViewModel(
    application: Application,
    private val categoryId: String,
) : AndroidViewModel(application) {

    private val repo = container.libraryRepository

    /** Null while the first Room emission is pending, so the UI can tell loading from empty. */
    val videos: StateFlow<List<VideoEntity>?> = repo.observeVideosByCategory(categoryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bulkFetch: StateFlow<BulkFetch> = repo.bulkFetch

    private val _creatingPlaylist = MutableStateFlow(false)
    val creatingPlaylist: StateFlow<Boolean> = _creatingPlaylist.asStateFlow()

    /** One-shot outcome message for playlist creation; consume after showing. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** Creates the playlist in the ViewModel scope, so rotation can't abort it mid-flight. */
    fun createPlaylist(name: String) {
        if (_creatingPlaylist.value) return
        _creatingPlaylist.value = true
        viewModelScope.launch {
            val result = repo.createPlaylistFromCategory(categoryId, name)
            val resources = getApplication<Application>().resources
            _message.value = when (result) {
                is PlaylistResult.Success -> resources.getString(
                    R.string.playlist_created,
                    result.name,
                    resources.getQuantityString(R.plurals.video_count, result.count, result.count),
                )
                is PlaylistResult.Error -> result.message
            }
            _creatingPlaylist.value = false
        }
    }

    fun startFetchMissing() = repo.startFetchMissing()
    fun cancelFetchMissing() = repo.cancelFetchMissing()
    fun acknowledgeBulkFetch() = repo.acknowledgeBulkFetch()
}
