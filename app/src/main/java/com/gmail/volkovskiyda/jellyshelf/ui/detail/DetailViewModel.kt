package com.gmail.volkovskiyda.jellyshelf.ui.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.repository.FetchResult
import com.gmail.volkovskiyda.jellyshelf.data.repository.Settings
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Detail screen state: distinguishes "still loading" from "this id has no local row". */
sealed interface VideoDetailState {
    data object Loading : VideoDetailState
    data object NotFound : VideoDetailState
    data class Loaded(val video: VideoEntity) : VideoDetailState
}

class DetailViewModel(
    application: Application,
    private val youtubeId: String,
) : AndroidViewModel(application) {

    private val repo = container.libraryRepository

    val video: StateFlow<VideoDetailState> = repo.observeVideo(youtubeId)
        .map { it?.let(VideoDetailState::Loaded) ?: VideoDetailState.NotFound }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoDetailState.Loading)

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
        val current = (video.value as? VideoDetailState.Loaded)?.video ?: return
        viewModelScope.launch {
            // The local toggle always sticks; tell the user when the server write failed,
            // since the next sync may revert it to the server's value.
            if (!repo.setPlayed(youtubeId, !current.played)) {
                _message.value = getApplication<Application>().getString(R.string.watch_state_sync_failed)
            }
        }
    }

    /** Repository-scoped, so the report survives leaving this screen mid-write. */
    fun reportPlaybackStopped(positionMs: Long, completed: Boolean) {
        Log.d(Playback.TAG, "DetailViewModel.reportPlaybackStopped: youtubeId=$youtubeId positionMs=$positionMs completed=$completed")
        repo.reportPlaybackStopped(youtubeId, positionMs, completed)
    }

    /**
     * Fetch/refresh metadata with the bundled yt-dlp. The repository call runs non-cancellable
     * so rotation or popping the screen can't abort a fetch whose result is about to be
     * persisted; the fetching flag is cleared afterwards so the button can't stay stuck on
     * "Fetching…".
     */
    fun fetchMetadata() {
        if (_fetching.value) return
        _fetching.value = true
        viewModelScope.launch {
            runCatchingCancellable {
                val result = withContext(NonCancellable) { repo.fetchMetadata(youtubeId) }
                _message.value = when (result) {
                    is FetchResult.Success ->
                        getApplication<Application>().getString(R.string.metadata_updated_for, result.title)
                    is FetchResult.Error -> result.message
                }
            }.onFailure { e ->
                _message.value = e.message ?: e.javaClass.simpleName
            }
            _fetching.value = false
        }
    }
}
