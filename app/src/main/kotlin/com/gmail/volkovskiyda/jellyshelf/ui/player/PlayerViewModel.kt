package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import com.gmail.volkovskiyda.jellyshelf.playback.PlaybackService
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.parseTimecodes
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    app: Application,
    private val repo: LibraryRepository,
    private val libraryFilters: LibraryFilterState,
    private val youtubeId: String,
    private val origin: PlayerOrigin?,
) : ViewModel() {

    /**
     * What is playing *now*, which stops being the video the screen was opened with as soon as
     * the queue advances — by the next button or by a video ending. Everything the screen shows
     * about the video hangs off this, so a title and a chapter list can never describe the
     * previous item.
     */
    private val currentId = MutableStateFlow(youtubeId)

    /** The video row, for the title over the controls; null until it loads. */
    val video: StateFlow<Video?> = currentId
        .flatMapLatest { repo.observeVideo(it) }
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
            // playback. Anything else starts it — bare mediaIds, no URIs: the service resolves
            // the stream URL and seeds the saved resume position (see PlaybackService).
            if (controller.currentMediaItem?.mediaId != youtubeId) {
                val queue = playbackQueue(originIds(), youtubeId)
                controller.setMediaItems(
                    queue.ids.map { MediaItem.Builder().setMediaId(it).build() },
                    queue.startIndex,
                    // Unset, so the session seeds the position this video was left at.
                    C.TIME_UNSET,
                )
                controller.prepare()
                controller.play()
            }
            // A null item is the queue being cleared on the way out (see stopPlayback); keeping
            // the last id there leaves the title in place while the screen finishes leaving.
            controller.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentId.value = mediaItem?.mediaId ?: currentId.value
                }
            })
            _controller.value = controller
        }
    }

    /**
     * One snapshot of the origin list, not a live flow: a queue that re-shuffles under the user
     * because a sync landed mid-video is worse than a slightly stale one.
     *
     * The library case reads the list the screen last rendered rather than re-querying, because
     * that emission carries the search query and duration filter the user had applied — going
     * back to the repository would queue videos they had filtered away. It falls back to the
     * unnarrowed library only when there is no such emission, which today means a player restored
     * into a process that never rendered the library tab.
     */
    private suspend fun originIds(): List<String> = when (origin) {
        null -> emptyList()
        PlayerOrigin.Library ->
            libraryFilters.lastVideos.value?.items ?: repo.observeVideos().first()
        is PlayerOrigin.Category -> repo.observeVideosByCategory(origin.categoryId).first()
    }.map { it.youtubeId }

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
