package com.gmail.volkovskiyda.jellyshelf.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.repository.FetchResult
import com.gmail.volkovskiyda.jellyshelf.data.repository.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    application: Application,
    private val youtubeId: String,
) : AndroidViewModel(application) {

    private val repo = container.libraryRepository

    val video: StateFlow<VideoEntity?> = repo.observeVideo(youtubeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<Settings?> = container.settingsState

    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    /** One-shot user-facing message (metadata fetch outcome); consume after showing. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun toggleWatched() {
        val current = video.value ?: return
        viewModelScope.launch { repo.setPlayed(youtubeId, !current.played) }
    }

    /** Repository-scoped, so the report survives leaving this screen mid-write. */
    fun reportPlaybackStopped(positionMs: Long, completed: Boolean) {
        repo.reportPlaybackStopped(youtubeId, positionMs, completed)
    }

    /** Fetch/refresh metadata with the bundled yt-dlp; survives rotation via the ViewModel. */
    fun fetchMetadata() {
        if (_fetching.value) return
        _fetching.value = true
        viewModelScope.launch {
            val result = repo.fetchMetadata(youtubeId)
            _message.value = when (result) {
                is FetchResult.Success -> "Metadata updated for ${result.title}"
                is FetchResult.Error -> result.message
            }
            _fetching.value = false
        }
    }
}
