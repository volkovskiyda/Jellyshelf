package com.gmail.volkovskiyda.jellyshelf.ui.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.repository.TRACK_LIBRARY
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.util.Metrics
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.Spans
import com.gmail.volkovskiyda.jellyshelf.util.firstContent
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Detail screen state: distinguishes "still loading" from "this id has no local row". */
sealed interface VideoDetailState {
    data object Loading : VideoDetailState
    data object NotFound : VideoDetailState
    data class Loaded(val video: Video) : VideoDetailState
}

class DetailViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    settingsState: AppSettingsState,
    private val settingsRepository: SettingsRepository,
    metrics: Metrics,
    private val youtubeId: String,
) : ViewModel() {

    /**
     * Time to first content, measured here rather than in the repository: the flow underneath is
     * read by the player screen and by the service on every queue advance as well, and a span down
     * there would report a detail screen opening each time any of them read a row. A video that is
     * not there is a legitimate first emission and reports zero rows.
     */
    val video: StateFlow<VideoDetailState> = repo.observeVideo(youtubeId)
        .firstContent(metrics, Spans.DETAIL_LOAD, TRACK_LIBRARY) { if (it == null) 0 else 1 }
        .map { it?.let(VideoDetailState::Loaded) ?: VideoDetailState.NotFound }
        .stateIn(viewModelScope, WhileUiSubscribed, VideoDetailState.Loading)

    val settings: StateFlow<Settings?> = settingsState.settings

    /**
     * Every category this video belongs to, live — a metadata fetch that re-derives memberships
     * updates the section in place. The content picks which dimensions it shows.
     */
    val categories: StateFlow<List<Category>> = repo.observeCategoriesForVideo(youtubeId)
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    /** One-shot user-facing message (metadata fetch outcome); consume after showing. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Set once this video has been dropped from the local library, so the screen can leave rather
     * than sit on the "video not found" state its own deletion produced. Not derived from [video]
     * going NotFound — that also happens when a sync deletes the row underneath an open screen,
     * which shouldn't yank the user out of it.
     */
    private val _removed = MutableStateFlow(false)
    val removed: StateFlow<Boolean> = _removed.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** Persists the mode picked in the split button's dropdown — app-wide, like the setting. */
    fun setPlaybackMode(mode: PlaybackMode) {
        viewModelScope.launch { settingsRepository.setPlaybackMode(mode) }
    }

    fun toggleWatched() {
        val current = (video.value as? VideoDetailState.Loaded)?.video ?: return
        viewModelScope.launch {
            // The local toggle always sticks; tell the user when the server write failed,
            // since the next sync may revert it to the server's value.
            if (!repo.setPlayed(youtubeId, !current.played)) {
                _message.value = app.getString(R.string.watch_state_sync_failed)
            }
        }
    }

    /**
     * Drop this video from the local library — offered for videos the server no longer lists, so
     * the user doesn't have to wait out the sync grace period. Local only: nothing is deleted on
     * Jellyfin, and a video that is actually still there returns on the next sync.
     */
    fun removeFromLibrary() {
        if (_removed.value) return
        viewModelScope.launch {
            repo.removeVideo(youtubeId)
            _removed.value = true
        }
    }

    /** Repository-scoped, so the report survives leaving this screen mid-write. */
    fun reportPlaybackStopped(positionMs: Long, completed: Boolean) {
        Timber.tag(Playback.TAG).d(
            "DetailViewModel.reportPlaybackStopped: youtubeId=$youtubeId " +
                "positionMs=$positionMs completed=$completed",
        )
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
                        app.getString(R.string.metadata_updated_for, result.title)
                    is FetchResult.Error -> result.message
                }
            }.onFailure { e ->
                _message.value = e.message ?: e.javaClass.simpleName
            }
            _fetching.value = false
        }
    }
}
