package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.playback.PlaybackService
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.parseTimecodes
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Connects the player screen to [PlaybackService]'s session. Owns a [MediaController] for the
 * screen's lifetime — built asynchronously here, released in [onCleared].
 *
 * Stopping is an explicit act, never a lifecycle one: [stopPlayback] is called from the screen's
 * back paths (done watching), while merely leaving the app keeps playback and the notification
 * alive (still watching). That is why [onCleared] only releases the controller — a stop from here
 * would also fire on a configuration change, and this screen rotates freely.
 */
class PlayerViewModel(
    app: Application,
    repo: LibraryRepository,
    private val youtubeId: String,
) : ViewModel() {

    /** The video row, for the title over the controls; null until it loads. */
    val video: StateFlow<Video?> = repo.observeVideo(youtubeId)
        .stateIn(viewModelScope, WhileUiSubscribed, null)

    /**
     * The player's chapters: description-parsed timecodes first ([parseTimecodes]), structured
     * yt-dlp chapters filling the gap — description wins when both exist (locked priority).
     */
    val chapters: StateFlow<List<Chapter>> = video
        .map { video ->
            parseTimecodes(video?.description, video?.durationSeconds ?: 0L)
                .ifEmpty { video?.chapters.orEmpty() }
        }
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    init {
        viewModelScope.launch {
            val controller = runCatchingCancellable { controllerFuture.await() }.getOrElse { e ->
                Timber.tag(Playback.TAG).e(e, "MediaController connection failed")
                return@launch
            }
            // Already on this video (reopened from the notification): attach without touching
            // playback. Anything else starts it — a bare mediaId, no URI: the service resolves
            // the stream URL and seeds the saved resume position (see PlaybackService).
            if (controller.currentMediaItem?.mediaId != youtubeId) {
                controller.setMediaItem(MediaItem.Builder().setMediaId(youtubeId).build())
                controller.prepare()
                controller.play()
            }
            _controller.value = controller
        }
    }

    /**
     * Explicit leave: stop, let the service file the one stop report, drop the notification.
     *
     * The pause is what makes that report carry the real position rather than one up to the
     * service's save interval stale — [PlaybackService] only records the position on a pause that
     * finds the player `STATE_READY`, and after a bare stop it is `IDLE`. Clearing the queue then
     * fires `onMediaItemTransition(null)`, which is the *single* stop report; reporting from here
     * as well would double-report, since the repository call is fire-and-forget and undeduped.
     */
    fun stopPlayback() {
        val controller = _controller.value ?: return
        controller.pause()
        controller.clearMediaItems()
    }

    override fun onCleared() {
        _controller.value = null
        MediaController.releaseFuture(controllerFuture)
    }
}
