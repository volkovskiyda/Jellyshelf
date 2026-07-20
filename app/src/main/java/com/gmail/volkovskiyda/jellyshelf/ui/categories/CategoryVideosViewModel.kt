package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    val videos: StateFlow<List<VideoEntity>> = repo.observeVideosByCategory(categoryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            _message.value = when (result) {
                is PlaylistResult.Success ->
                    "Created \"${result.name}\" with ${result.count} video${if (result.count == 1) "" else "s"}"
                is PlaylistResult.Error -> result.message
            }
            _creatingPlaylist.value = false
        }
    }

    fun startFetchMissing() = repo.startFetchMissing()
    fun cancelFetchMissing() = repo.cancelFetchMissing()
    fun acknowledgeBulkFetch() = repo.acknowledgeBulkFetch()
}
