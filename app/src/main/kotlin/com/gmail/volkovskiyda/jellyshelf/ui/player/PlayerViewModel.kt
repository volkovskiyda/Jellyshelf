package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import com.gmail.volkovskiyda.jellyshelf.playback.PlaybackService
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.embeddedChapters
import com.gmail.volkovskiyda.jellyshelf.util.parseTimecodes
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 *
 * The two runtime arguments arrive as the one [AppNavKey.Player] they already are, rather than as
 * a loose id and origin: it is a single value the navigation layer hands around whole, and Koin
 * matches `parametersOf` by type, so one key is also one fewer type to keep distinct there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    app: Application,
    private val repo: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val libraryFilters: LibraryFilterState,
    key: AppNavKey.Player,
) : ViewModel() {

    private val youtubeId = key.youtubeId
    private val origin = key.origin

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
     * Chapters the media file carries itself, read by media3's extractors — empty for everything
     * this app streams from a server. Last in [chapters]' order, and see [embeddedChapters].
     */
    private val embeddedChapters = MutableStateFlow<List<Chapter>>(emptyList())

    /**
     * The player's chapters: description-parsed timecodes first ([parseTimecodes]), structured
     * yt-dlp chapters filling the gap — description wins when both exist (locked priority) —
     * and, only when neither exists, whatever the container itself declares ([embeddedChapters]).
     *
     * The container goes last deliberately: it is the source we know least about. A Jellyfin video
     * has chapters that came from the uploader's own description or from yt-dlp, and a remuxed file
     * can carry stale ones from whatever produced it. This changes nothing for a synced video and
     * everything for a local file that has no other source.
     */
    val chapters: StateFlow<List<Chapter>> = combine(video, embeddedChapters) { video, embedded ->
        parseTimecodes(video?.description, video?.durationSeconds ?: 0L)
            .ifEmpty { video?.chapters.orEmpty() }
            .ifEmpty { embedded }
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
                // Whatever else was playing ends before the queue is even assembled: the origin
                // snapshot and the session's resolve of every queued id both take long enough
                // that the previous video would otherwise keep playing — and, since the surface
                // above is already attached to it, keep *rendering* — over the one being opened.
                // The same pause-then-clear as [stopPlayback]: the pause is what makes the old
                // video's stop report carry its real position, the clear is what files it.
                // Usually a no-op, because the tap that got here has already done it (see
                // MainActivity's openPlayer); this covers the ways in that did not, such as a
                // player restored into a process where playback was already under way.
                if (controller.currentMediaItem != null) {
                    controller.pause()
                    controller.clearMediaItems()
                }
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
                    // The new item's tracks have not been read yet, and the old item's chapters
                    // must not survive into it — a stale list is worse than none, since every
                    // entry seeks to the wrong place.
                    embeddedChapters.value = emptyList()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    embeddedChapters.value = tracks.embeddedChapters()
                }
            })
            // A listener replays no history: attaching to playback already under way — reopened
            // from the bar or the notification — means the current item's tracks were read before
            // this controller connected, and no onTracksChanged for them will ever arrive. Seeding
            // from the snapshot is what keeps a container's chapters on screen across a
            // minimize-and-reopen; for a queue this init just started, currentTracks is still
            // empty and this writes the emptyList it already holds.
            embeddedChapters.value = controller.currentTracks.embeddedChapters()
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
        PlayerOrigin.None -> emptyList()
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
     * (Leaving a video that played to its end reports nothing here — its completion was already
     * filed when it ended; see [com.gmail.volkovskiyda.jellyshelf.playback.WatchStateTracker].)
     */
    fun stopPlayback() {
        val controller = _controller.value ?: return
        controller.pause()
        controller.clearMediaItems()
    }

    /**
     * Remembers a speed picked from the speed menu as the one later playback starts at
     * ([PlaybackService] applies it when it builds the player). Applying it to the *current*
     * player is the caller's job — media3's speed-button state does that — so this is only the
     * write. Press-and-hold's temporary 3× never comes through here.
     *
     * On [DispatcherProvider.applicationScope] rather than [viewModelScope] because picking a
     * speed and leaving is one gesture — 2×, then Back — and a `viewModelScope` write would be
     * cancelled by [onCleared] before it reached disk. Fire-and-forget; a failed write just means
     * the next launch starts at the previously saved speed.
     */
    fun savePlaybackSpeed(speed: Float) {
        dispatchers.applicationScope.launch { settingsRepository.setPlaybackSpeed(speed) }
    }

    override fun onCleared() {
        _controller.value = null
        MediaController.releaseFuture(controllerFuture)
    }
}
