@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.tracing.traceAsync
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_ITEM_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlayMethod
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.Traces
import com.gmail.volkovskiyda.jellyshelf.util.authorizedImageUrl
import com.gmail.volkovskiyda.jellyshelf.util.ticksToMillis
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import androidx.tracing.Trace as SystemTrace

/**
 * The app's playback engine: one [ExoPlayer] owned by a [MediaSessionService], so playback
 * outlives the player screen (background audio, media notification) and system surfaces can
 * command it through the session.
 *
 * Controllers send bare ids — `MediaItem(mediaId = youtubeId)` and nothing else, because a
 * MediaItem's localConfiguration does not survive controller→session transit even in-process.
 * [SessionCallback] resolves the id against the library: stream URL, notification metadata and
 * the saved resume position. It can seed that position only for the video a queue *opens* on —
 * media3 takes one start position per playlist — so [ResumeSeedingListener] covers the rest of
 * the queue as it is reached.
 *
 * The credential travels only as an `X-Emby-Token` request header ([Playback.TOKEN_HEADER]).
 * The `tokenInQuery` setting is an escape hatch for external players and is deliberately not
 * consulted here.
 *
 * Direct play first, transcoding second: media3's ffmpeg software decoders are not published
 * on Maven, so an exotic codec inside an mkv/webm may not decode on some devices. When that
 * happens ([isDecodeFailure]), [TranscodeFallbackListener] swaps the item for the server's HLS
 * transcode ([Playback.hlsUrl]) at the same position — once; a failure of the transcode itself
 * surfaces, and the player screen's toast points at the External playback mode.
 *
 * Server reporting follows Jellyfin's own session flow while a video plays here: a start report
 * once it is genuinely under way, progress every [POSITION_SAVE_INTERVAL_MS] (and once more on a
 * pause), and a stop report with the final position ([LibraryRepository.reportPlaybackStopped]) —
 * so the server's resume thresholds, not this app, decide when a video counts as watched. The
 * external-player flow keeps its single stop report and its direct playstate write. Periodic
 * local-only saves ([LibraryRepository.savePlaybackPosition]) run alongside either way, so process
 * death can't lose the position.
 */
// The session callback and player listener are inner classes by design — they are this
// service's behavior and share its single-threaded state; the synthetic accessors that
// generates are negligible next to the session machinery itself.
@SuppressLint("SyntheticAccessor")
class PlaybackService : MediaSessionService(), KoinComponent {

    // Framework-instantiated, so dependencies resolve via Koin instead of the constructor.
    private val repo: LibraryRepository by inject()
    private val settingsState: AppSettingsState by inject()
    private val nowPlaying: NowPlayingState by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val dispatchers: DispatcherProvider by inject()
    private val resumable: ResumableCache by inject()
    private val buildInfo: BuildInfo by inject()

    // The player's application thread is this service's main thread; session callbacks, the
    // listener and the periodic saver all stay on it, which is also what makes the plain-var
    // bookkeeping below safe. Since media3 1.11.0 that is a hard rule rather than a convention —
    // its state accessors throw when read from another thread, where void methods still auto-post
    // — so [checkOnApplicationLooper] pins it on the one entry point that comes from outside.
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    /** Which video is active, where it reached, and what has been reported — the rules live here. */
    private val watch = WatchStateTracker()
    private var saveJob: Job? = null

    /** The slow keep-alive that runs only while playback sits paused; see [startPausedKeepAlive]. */
    private var pausedJob: Job? = null

    /**
     * The user-perceived startup being timed: set when a controller hands the session a queue,
     * consumed by [StartupTraceListener] at the first rendered frame. A trace that is replaced
     * or abandoned is deliberately never stopped — an unstopped trace is never reported.
     */
    private var startupTrace: Trace? = null

    /**
     * The open cookies of the two async trace sections in [Traces], or null when none is running.
     *
     * Kept as fields rather than derived at the end, because an async section has to be closed with
     * the *same* cookie it was opened with and the media item it came from may already have changed
     * by then. A section left open is worse than an unstopped Firebase trace — Perfetto draws it as
     * a slice running to the end of the capture — so every path that abandons [startupTrace]
     * clears these too.
     */
    private var startupSectionCookie: Int? = null
    private var transitionSectionCookie: Int? = null

    override fun onCreate() {
        super.onCreate()
        // A source per stream, reading the credential at creation time so a re-login between
        // plays is picked up without restarting the service.
        val httpFactory = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(
                    mapOf(Playback.TOKEN_HEADER to settingsState.settings.value?.credential.orEmpty()),
                )
                .createDataSource()
        }
        // Media3's standard composition: `asset:` and `file:` URIs — which is what a demo video
        // resolves to — route to local sources, while every http(s) URI is handed to the factory
        // above and behaves exactly as it did before, credential-at-creation-time included.
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // handleAudioFocus: pause for calls, duck for notifications, like any video app.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_INCREMENT_MS)
            .build()
        // A few seconds of the *next* queued video, buffered while this one plays: pressing Next
        // and reaching the end of a video both then start on a stream that is already open, which
        // is the whole wait for anything the user did not have to choose. Only the next item is
        // touched, however long the queue is, so a library-sized queue costs the same as a
        // single-video one — and nothing at all when there is no next item.
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_TARGET_DURATION_US)
        player.addListener(WatchStateListener())
        player.addListener(StartupTraceListener())
        player.addListener(TranscodeFallbackListener())
        // Last, so a transition has already been reported and re-tracked before this seeks.
        player.addListener(ResumeSeedingListener())
        this.player = player
        // The mini-player bar's three buttons, on the real player. Registered here rather than
        // handed a controller, because a controller is what *starts* this service.
        nowPlaying.attach(object : NowPlayingState.Transport {
            override fun playPause() {
                // media3's own helper, the one the player screen's button state uses, so the bar
                // and the screen cannot disagree about what a play tap does to an ended video.
                this@PlaybackService.player?.let {
                    it.checkOnApplicationLooper(buildInfo.isDebug)
                    Util.handlePlayPauseButtonAction(it)
                }
            }

            override fun next() {
                // Deliberately seekToNextMediaItem, not seekToNext: the latter restarts the
                // current video once past its threshold, which is a different button entirely.
                val p = this@PlaybackService.player ?: return
                p.checkOnApplicationLooper(buildInfo.isDebug)
                // The guard is what makes the play() below safe. seekToNextMediaItem is already a
                // no-op at the end of the queue, but play() is not: without this, a tap that
                // arrived through a stale hasNext would resume the *current* paused video.
                if (!p.hasNextMediaItem()) return
                p.seekToNextMediaItem()
                // The bar's next starts playback rather than carrying a pause forward: on a
                // glanceable surface with no other feedback, loading the next video paused reads
                // as a dead tap. Deliberately unlike the player screen's next, where the full
                // transport is visible and a paused seek is legible.
                p.play()
            }

            override fun stop() {
                // Exactly PlayerViewModel.stopPlayback: the pause is what makes the stop report
                // carry the real position (the tracker only records one from a STATE_READY pause),
                // and clearing the queue is what fires the single stop report. Anything else here
                // either loses the position or reports twice.
                val p = this@PlaybackService.player ?: return
                p.checkOnApplicationLooper(buildInfo.isDebug)
                p.pause()
                p.clearMediaItems()
            }
        })
        restorePlaybackSpeed(player)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity(youtubeId = null))
            .setCallback(SessionCallback())
            .build()
    }

    /**
     * Starts the fresh player at the speed the user last picked from the player's speed menu.
     *
     * On the player rather than on a controller because the player is what owns a speed: this
     * covers every way playback can begin — the player screen, the media notification, a system
     * surface — and it runs once per service lifetime, before any controller can bind (a controller
     * connecting is what creates this service). ExoPlayer carries `PlaybackParameters` across
     * `setMediaItems`, so one restore is what makes it the default for the whole session.
     *
     * Normally the value is already in hand: [AppSettingsState] is eagerly started and the UI has
     * warmed it long before playback — the same synchronous read [onCreate] already makes for the
     * credential — so nothing plays at 1× first. Only a UI-less cold start (a notification restore)
     * waits for the first read, and there the guard is what stops a stale disk value from undoing a
     * speed that has moved in the meantime.
     */
    private fun restorePlaybackSpeed(player: ExoPlayer) {
        val known = settingsState.settings.value
        if (known != null) {
            player.setPlaybackSpeed(known.playbackSpeed)
            return
        }
        scope.launch {
            val persisted = settingsState.settings.filterNotNull().first().playbackSpeed
            // The service was torn down while the read suspended, or the speed has since moved (a
            // menu pick, or press-and-hold) — either way the disk value is stale before it landed.
            if (this@PlaybackService.player === player &&
                player.playbackParameters.speed == PlaybackSpeed.DEFAULT
            ) {
                player.setPlaybackSpeed(persisted)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // The final stop report — fire-and-forget on the repository's own scope, so it survives
        // this service going away (swipe from recents, system stop).
        val p = player
        if (p != null) watch.onDestroy(p.currentPosition).perform()
        // A teardown mid-startup (swiped from recents before the first frame) is the one abandon
        // path the queue-empty close in WatchStateListener cannot see.
        startupSectionCookie = endSection(Traces.PLAYER_STARTUP, startupSectionCookie)
        transitionSectionCookie = endSection(Traces.PLAYER_TRANSITION, transitionSectionCookie)
        nowPlaying.detach()
        session?.release()
        p?.release()
        session = null
        player = null
        serviceJob.cancel()
        super.onDestroy()
    }

    /**
     * Resolves the bare media ids controllers send into playable items: the stream URL, the
     * notification metadata, and — for a plain `setMediaItem` — the saved resume position.
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            scope.future { mediaItems.map { resolve(it) }.toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // The closest observable moment to the user's tap: startup includes resolving the
            // queue below, preparing, and possibly the transcode fallback — everything between
            // here and the first rendered frame. Names are API (the console keys off them):
            // trace "player_startup", attribute "source" (direct|hls). Replacing a still-pending
            // trace abandons it: the user gave up on that startup and began another.
            startupTrace = FirebasePerformance.getInstance().newTrace("player_startup")
                .apply { start() }
            // The local mirror of that trace, readable by a macrobenchmark without a release build
            // — see [Traces.PLAYER_STARTUP]. Same lifecycle as the Firebase one, abandonment
            // included: replacing a pending startup closes the section that was measuring it,
            // rather than leaking a slice that runs to the end of the capture.
            startupSectionCookie?.let { SystemTrace.endAsyncSection(Traces.PLAYER_STARTUP, it) }
            startupSectionCookie = mediaItems.firstOrNull()?.mediaId.hashCode().also {
                SystemTrace.beginAsyncSection(Traces.PLAYER_STARTUP, it)
            }
            return scope.future {
                // An *async* section, not a `trace { }` one: resolving suspends on DataStore and
                // Room, so it can resume on a thread other than the one it began on, and
                // beginSection/endSection are thread-confined — a plain section would be closed on
                // the wrong track or not at all. The cookie is what lets two overlapping startups
                // be told apart, so it comes from the video being started rather than being a
                // constant. See [Traces.PLAYER_RESOLVE].
                val resolved = traceAsync(
                    Traces.PLAYER_RESOLVE,
                    mediaItems.firstOrNull()?.mediaId.hashCode(),
                ) {
                    mediaItems.map { resolve(it) }
                }
                if (startPositionMs != C.TIME_UNSET || resolved.isEmpty()) {
                    // A controller that wants a specific position passes one — the transcode
                    // fallback, which must land exactly where the failed decode left off.
                    return@future MediaSession.MediaItemsWithStartPosition(
                        resolved,
                        startIndex,
                        startPositionMs,
                    )
                }
                // The resume path: no position asked for, so the item being started begins where
                // it was last left. The index matters as much as the position — a queue names the
                // video it opens on, and reading the resume ticks of item 0 instead would restore
                // a position from a different video (or, before this branch covered an explicit
                // index at all, start every queued video at 0).
                val index =
                    if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(resolved.indices)
                val resumeTicks =
                    repo.observeVideo(resolved[index].mediaId).first()?.playbackPositionTicks ?: 0L
                MediaSession.MediaItemsWithStartPosition(resolved, index, ticksToMillis(resumeTicks))
            }
        }

        /**
         * What the system's own resumption surfaces get when they ask: the output switcher, a
         * Bluetooth or headset play button, a reboot. They ask a *dead* process, so everything
         * here is read back from storage rather than from any live state.
         *
         * The three-argument form, which is the one media3 actually invokes (`MediaSessionImpl`
         * calls it directly; its two-argument sibling is deprecated and only reached through this
         * one's default implementation). [isForPlayback] is false when the system wants the
         * metadata to *offer* a resumption rather than to start one — the answer is the same
         * either way, and media3 decides whether to press play.
         *
         * A single-item queue, matching the notification-reopen path. The list the video was
         * originally played from died with the process, and inventing one would queue up videos
         * the user never chose.
         *
         * Failing the future is the correct answer to "nothing to resume" — the system then shows
         * nothing at all, which is what should happen after an explicit stop, after the video has
         * left the library, or while signed out.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val lastPlayedId = settingsRepository.lastPlayedVideoId.first()
            val video = lastPlayedId?.let { repo.observeVideo(it).first() }
            val request = resumptionRequest(
                lastPlayedId = lastPlayedId,
                videoExists = video != null,
                savedTicks = video?.playbackPositionTicks ?: 0L,
                played = video?.played == true,
            )
            // resolve() hands back a URI-less item when the row cannot be turned into a stream —
            // signed out, or a demo row left behind by a half-cleared demo. Playing that would
            // surface as an ExoPlayer source error with no screen to show it on.
            val item = request
                ?.let { resolve(MediaItem.Builder().setMediaId(it.youtubeId).build()) }
                ?.takeIf { it.localConfiguration != null }
            if (request == null || item == null) {
                // Reached only when [MediaButtonGate] said yes and the answer turned out to be
                // no — the video has left the library since, or the session is signed out — or
                // when the asker was not a media button at all. Correcting the mirror is what
                // makes that self-healing: the next media button is declined outright, before a
                // service that cannot play is ever started.
                resumable.store(false)
                error("nothing to resume")
            }
            MediaSession.MediaItemsWithStartPosition(
                listOf(item),
                0,
                request.startPositionMs,
            )
        }
    }

    private suspend fun resolve(item: MediaItem): MediaItem {
        // Suspends until the first DataStore read lands; null only in the first process moments.
        val settings = settingsState.settings.filterNotNull().first()
        val video = repo.observeVideo(item.mediaId).first()
        val jellyfinItemId = video?.jellyfinItemId
        if (video == null || jellyfinItemId == null) {
            // Left URI-less on purpose: ExoPlayer raises a source error the player screen shows.
            Timber.tag(Playback.TAG).w("resolve: no playable row for mediaId=${item.mediaId}")
            return item
        }
        val artworkUrl = authorizedImageUrl(video.thumbnailUrl, settings.serverUrl, settings.credential)
        // Demo rows have no server behind them, so there is no stream URL to build — every one of
        // them plays the bundled clip. Keyed off the sentinel item id rather than the demoMode
        // setting: it is the row that is or isn't playable, and a row outliving the flag (a demo
        // half-cleared by a crash) must not turn into a request against a blank server URL.
        val uri = if (jellyfinItemId == DEMO_ITEM_ID) {
            DEMO_SAMPLE_URI
        } else {
            Playback.streamUrl(settings.serverUrl, jellyfinItemId, credential = null)
        }
        return item.buildUpon()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channel)
                    // Query-credentialed: the session's bitmap loader fetches it with no headers.
                    // A demo row's artwork is a `file:///android_asset/` URL, which the loader
                    // reads directly — verified on device; the notification shows the thumbnail.
                    .setArtworkUri(artworkUrl?.toUri())
                    .build(),
            )
            .build()
    }

    /**
     * Mirrors playback into watch state through the same paths the external-player flow uses:
     * one full stop report per finished/abandoned video, periodic local-only saves in between.
     */
    private inner class WatchStateListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // What a queue advance costs, measured to the new item's first frame in
            // [StartupTraceListener.onRenderedFirstFrame] — see [Traces.PLAYER_TRANSITION].
            //
            // Gated on the reason: PLAYLIST_CHANGED is the queue's *first* item arriving, which is
            // a startup and is already measured as one. Counting it here as well would make the
            // two metrics move together and stop either from isolating anything. A null item is the
            // queue emptying, which is not an advance either.
            if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                transitionSectionCookie = endSection(Traces.PLAYER_TRANSITION, transitionSectionCookie)
                transitionSectionCookie = mediaItem.mediaId.hashCode().also {
                    SystemTrace.beginAsyncSection(Traces.PLAYER_TRANSITION, it)
                }
            }
            if (mediaItem == null) {
                // The queue emptied with a startup or transition still measuring — a stop landed
                // between a queue advance (or a tap) and the frame that would have closed it.
                // Nothing will ever render that frame now, so close here or the slice runs to the
                // end of the capture and corrupts the very metric the benchmark reads. The
                // Firebase startup trace needs no twin of this: a trace never stop()ped is simply
                // never reported.
                startupSectionCookie = endSection(Traces.PLAYER_STARTUP, startupSectionCookie)
                transitionSectionCookie = endSection(Traces.PLAYER_TRANSITION, transitionSectionCookie)
            }
            watch.onItemChanged(
                newMediaId = mediaItem?.mediaId,
                autoAdvance = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            ).perform()
            // A new video gets its own save interval rather than inheriting the previous one's
            // phase — otherwise a tick due in the next few milliseconds could save it at position
            // zero, beating [ResumeSeedingListener] to the row it is about to resume from.
            if (player?.isPlaying == true) startPeriodicSave()
            // Whatever was paused is gone; the new item has no session to keep alive yet.
            pausedJob?.cancel()
            // A notification tap should reopen the player on whatever is playing now.
            session?.setSessionActivity(sessionActivity(mediaItem?.mediaId))
            // The same moment tells the mini-player bar what to draw — the metadata is the one
            // resolve() attached, so the bar shows the same title and artwork the notification
            // does. A null item is the queue emptying, which is the bar's cue to go away.
            // On the application scope, not this service's: the clear below happens as the queue
            // empties, which is immediately before the service is torn down, and a write on the
            // service scope would be cancelled with it — leaving a video the user explicitly
            // stopped on offer in the system's resumption UI.
            dispatchers.applicationScope.launch {
                settingsRepository.setLastPlayedVideoId(mediaItem?.mediaId)
            }
            // The synchronous mirror a media button is answered from, written here so the two
            // never disagree about whether there is anything to come back to.
            resumable.store(mediaItem != null)
            nowPlaying.show(
                mediaItem?.let {
                    NowPlaying(
                        youtubeId = it.mediaId,
                        title = it.mediaMetadata.title?.toString(),
                        artworkUri = it.mediaMetadata.artworkUri?.toString(),
                        isPlaying = player?.isPlaying == true,
                        hasNext = player?.hasNextMediaItem() == true,
                    )
                },
            )
        }

        /**
         * The queue's far end, for the mini-player bar's next button. Here as well as in
         * [onMediaItemTransition] because the queue arrives asynchronously — the session resolves
         * every id before the timeline exists — so the transition that first showed the bar can
         * precede the timeline that says whether anything follows.
         *
         * hasNextMediaItem() respects shuffle order and repeat mode, neither of which this app
         * sets. Using the player's own accessor is the right call rather than a shortcut: the
         * bar's button calls seekToNextMediaItem(), whose behaviour tracks the same two settings.
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            nowPlaying.setHasNext(player?.hasNextMediaItem() == true)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            watch.onPositionDiscontinuity(
                oldMediaId = oldPosition.mediaItem?.mediaId,
                oldPositionMs = oldPosition.positionMs,
                newMediaId = newPosition.mediaItem?.mediaId,
                newPositionMs = newPosition.positionMs,
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            nowPlaying.setPlaying(isPlaying)
            if (isPlaying) {
                // Cancelled before the resume is reported, not after: both run on this thread, so
                // stopping the keep-alive here is what guarantees a stale "is paused" tick cannot
                // land behind the report that supersedes it.
                pausedJob?.cancel()
                watch.onPlaying(player?.currentPosition ?: 0L).perform()
                startPeriodicSave()
            } else {
                saveJob?.cancel()
                val p = player
                val ready = p?.playbackState == Player.STATE_READY
                watch.onPaused(positionMs = p?.currentPosition ?: 0L, ready = ready).perform()
                // Only a genuine pause is worth keeping alive. Buffering resolves itself, and an
                // ended video has already been reported — telling the server it is still sitting
                // there would leave a finished video "now playing" for as long as the app lives.
                if (ready) startPausedKeepAlive()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            watch.onEnded(player?.duration?.takeIf { it != C.TIME_UNSET }).perform()
        }

        /**
         * Shapes the Picture-in-Picture window. Reported here rather than read off the player
         * screen because it arrives from the decoder, which is this service's business and
         * outlives any screen — and because the activity has to know the shape *before* the user
         * leaves, which is exactly when the screen stops being able to tell it anything.
         */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            nowPlaying.setVideoSize(videoSize.width, videoSize.height)
        }
    }

    /** Carries out what [WatchStateTracker] decided; null means there was nothing to do. */
    private fun WatchAction?.perform() {
        when (this) {
            null -> Unit
            is WatchAction.Report ->
                repo.reportPlaybackStopped(youtubeId, positionMs, completed, playSessionId)
            is WatchAction.SessionStart ->
                repo.reportPlaybackStarted(youtubeId, positionMs, playSessionId, currentPlayMethod())
            is WatchAction.Progress ->
                repo.reportPlaybackProgress(youtubeId, positionMs, isPaused, playSessionId, currentPlayMethod())
            is WatchAction.Save -> repo.savePlaybackPosition(youtubeId, positionMs)
        }
    }

    /** The same for a moment that decided several things at once, in the order the tracker chose. */
    private fun List<WatchAction>.perform() = forEach { it.perform() }

    /**
     * How the server is delivering what is loaded right now, resolved at send time rather than
     * carried through the tracker: it can change mid-video, and only the player knows.
     *
     * The same URI check [StartupTraceListener] makes — an item playing the HLS playlist is one the
     * transcode fallback swapped in; everything else is the direct stream this app asks for first.
     * A demo clip is neither, but it never reaches a report: those are gated out in the repository.
     */
    private fun currentPlayMethod(): PlayMethod {
        val uri = player?.currentMediaItem?.localConfiguration?.uri
        return if (uri?.lastPathSegment == Playback.HLS_PLAYLIST) {
            PlayMethod.Transcode
        } else {
            PlayMethod.DirectPlay
        }
    }

    /**
     * Starts each video the queue moves to where the user last left it — see [resumeSeekMs] for
     * which moves count and why the others must not.
     *
     * The lookup is asynchronous, so the video plays from its start for the moment it takes Room
     * to answer. That is deliberate: blocking the transition on a disk read would stall playback
     * for every video, including the ones with nothing to resume.
     */
    private inner class ResumeSeedingListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId ?: return
            val p = player ?: return
            scope.launch {
                val ticks = repo.observeVideo(mediaId).first()?.playbackPositionTicks ?: 0L
                val seekMs = resumeSeekMs(reason, ticks) ?: return@launch
                // The service was torn down, or the queue moved on again, while the lookup
                // suspended — seeking now would land in whatever is playing instead.
                if (player !== p || p.currentMediaItem?.mediaId != mediaId) return@launch
                Timber.tag(Playback.TAG).d("resuming $mediaId at ${seekMs}ms")
                p.seekTo(seekMs)
            }
        }
    }

    /**
     * Closes [startupTrace] at the moment the startup it measures becomes visible — the first
     * rendered frame — tagging its `source` as `direct` or `hls` (a startup that went through the
     * transcode fallback, whose durations will naturally run worse). A demo startup is abandoned
     * instead: the bundled asset plays locally and would only pollute the duration distribution
     * (the same reason demo syncs are untraced). A terminal error abandons the pending trace too,
     * so a much-later manual retry can't report the idle time in between as a minutes-long
     * "startup" — but a decode failure the transcode fallback is about to retry keeps it pending
     * on purpose, because that retry is part of what the user waits through.
     */
    private inner class StartupTraceListener : Player.Listener {
        override fun onRenderedFirstFrame() {
            // The two system-trace sections close first and unconditionally, before the Firebase
            // trace's own rules get a say: they are a local measurement and have neither its
            // demo-clip skip nor its release-only gating. Kept apart from the Firebase bookkeeping
            // below so a reader can see which is which.
            endPlaybackSections()

            val trace = startupTrace ?: return
            startupTrace = null
            val uri = player?.currentMediaItem?.localConfiguration?.uri
            if (uri?.scheme == DEMO_SAMPLE_SCHEME) return
            trace.putAttribute(
                "source",
                if (uri?.lastPathSegment == Playback.HLS_PLAYLIST) "hls" else "direct",
            )
            trace.stop()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Mirrors [TranscodeFallbackListener]'s retry decision; keep the two in step.
            val uri = player?.currentMediaItem?.localConfiguration?.uri
            val retrying = error.isDecodeFailure() && uri != null &&
                uri.scheme != DEMO_SAMPLE_SCHEME && uri.lastPathSegment != Playback.HLS_PLAYLIST
            if (!retrying) {
                startupTrace = null
                // A startup that will never render a frame: close its sections here or they run to
                // the end of the capture. A retry keeps them open on purpose — the transcode
                // fallback is part of what the startup cost, not a separate one.
                endPlaybackSections()
            }
        }

        /**
         * Closes whichever of [Traces.PLAYER_STARTUP] and [Traces.PLAYER_TRANSITION] is open, with
         * the cookie each was opened with. Safe to call when neither is.
         */
        private fun endPlaybackSections() {
            startupSectionCookie = endSection(Traces.PLAYER_STARTUP, startupSectionCookie)
            transitionSectionCookie = endSection(Traces.PLAYER_TRANSITION, transitionSectionCookie)
        }
    }

    /**
     * Retries an undecodable direct-play item as the server's HLS transcode, at the position the
     * failure left off. The rebuilt item keeps its media id and metadata, so watch-state
     * tracking carries over untouched, and its playlist URL marks it as already-transcoding —
     * which is what keeps a failing transcode from looping instead of surfacing.
     */
    private inner class TranscodeFallbackListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val failed = p.currentMediaItem ?: return
            val uri = failed.localConfiguration?.uri
            // A demo item has no server to transcode it: swapping in an HLS URL built from a blank
            // server URL would turn "this bundled clip would not decode" into a confusing network
            // error. Let the real one surface instead. (In practice the bundled H.264/AAC clip
            // decodes everywhere; this is about never converting an impossible state into a lie.)
            if (uri?.scheme == DEMO_SAMPLE_SCHEME) {
                Timber.tag(Playback.TAG).e(error, "demo clip failed to play: ${error.errorCodeName}")
                return
            }
            if (!error.isDecodeFailure() || uri == null || uri.lastPathSegment == Playback.HLS_PLAYLIST) {
                Timber.tag(Playback.TAG).e(error, "playback error, not retrying: ${error.errorCodeName}")
                return
            }
            // An error keeps the player's position; the rebuilt item resumes right there.
            val resumeMs = p.currentPosition
            scope.launch {
                val settings = settingsState.settings.filterNotNull().first()
                val itemId = repo.observeVideo(failed.mediaId).first()?.jellyfinItemId ?: return@launch
                // The service was torn down while the lookups suspended.
                if (player !== p) return@launch
                Timber.tag(Playback.TAG).w(
                    "direct play failed (${error.errorCodeName}); retrying as HLS transcode at ${resumeMs}ms",
                )
                val transcoded = failed.buildUpon()
                    .setUri(Playback.hlsUrl(settings.serverUrl, itemId))
                    .build()
                p.setMediaItem(transcoded, resumeMs)
                p.prepare()
                p.play()
            }
        }
    }

    private fun startPeriodicSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                player?.currentPosition?.let { watch.onPeriodicTick(it).perform() }
            }
        }
    }

    /**
     * Keeps a paused session alive. Left unheard from, the server does not merely drop the row from
     * its dashboard — it files a stop against the session at the position it last knew, which lands
     * on the app as a watch it never reported (and, past the server's own threshold, as watched).
     *
     * Deliberately much slower than the playing tick: a paused video can sit for hours with nothing
     * about it changing, so the only job here is to beat the server's window — see
     * [PAUSED_REPORT_INTERVAL_MS] for what that window actually is.
     *
     * The tracker answers with nothing at all when no session is open, so a video paused before it
     * was ever reported stays as silent as it is today.
     */
    private fun startPausedKeepAlive() {
        pausedJob?.cancel()
        pausedJob = scope.launch {
            while (isActive) {
                delay(PAUSED_REPORT_INTERVAL_MS)
                player?.currentPosition?.let { watch.onPausedKeepAlive(it).perform() }
            }
        }
    }

    companion object {
        /**
         * Intent extra on [MainActivity]: the youtubeId whose player screen a media-notification
         * tap should reopen.
         */
        const val EXTRA_OPEN_PLAYER = "com.gmail.volkovskiyda.jellyshelf.playback.OPEN_PLAYER"

        /**
         * The bundled clip every demo video plays — a Big Buck Bunny excerpt, CC-BY 3.0, credited
         * in the README. `asset:///` is media3's own scheme for APK assets, served by
         * [DefaultDataSource] rather than over HTTP.
         */
        private const val DEMO_SAMPLE_SCHEME = "asset"
        private const val DEMO_SAMPLE_URI = "$DEMO_SAMPLE_SCHEME:///demo/sample.mp4"

        /**
         * Deliberately asymmetric: skipping filler is the common case, re-hearing a line the rare
         * one. The player screen's buttons and its double-tap read these through media3's
         * seek-button states, so the two can never drift apart.
         */
        private const val SEEK_BACK_INCREMENT_MS = 10_000L
        private const val SEEK_FORWARD_INCREMENT_MS = 30_000L

        // Unrelated to the seek increments despite matching one of them today.
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L

        /**
         * How much of the next queued video to buffer ahead of reaching it.
         *
         * Enough to cover opening the stream and decoding a first frame, which is what the wait
         * at a queue advance is actually made of; more would be paid for on every video, in
         * traffic and in memory, to shorten a wait that is already gone. Three seconds of a
         * direct-played 1080p stream is a few megabytes against a server that is usually on the
         * same network.
         */
        private const val PRELOAD_TARGET_DURATION_US = 3_000_000L

        /**
         * How often a paused session tells the server it is still there.
         *
         * The deadline it has to beat is Jellyfin's `CheckForIdlePlayback`: every 5 minutes the
         * server stops playback on any session whose `LastPlaybackCheckIn` is more than 5 minutes
         * old. That threshold is hardcoded, not a server setting, and a paused progress report is
         * what refreshes the check-in — which is the whole reason this keep-alive exists.
         *
         * Two minutes leaves a whole report's worth of slack: one dropped send still checks in at
         * four minutes, inside the window. Anything faster only buys slack that is already there,
         * at four times the traffic for a video left paused overnight.
         *
         * It does *not* address the server's other timer, the one behind the `InactiveSessionThreshold`
         * setting — that one measures from the moment a session *entered* pause and no report while
         * paused moves it, so nothing sent from here can. It is off by default anyway.
         */
        private const val PAUSED_REPORT_INTERVAL_MS = 120_000L
    }
}

/**
 * What tapping the media notification opens: [MainActivity], carrying the id of the video playing
 * now so it lands on that player screen rather than on wherever the app was last.
 *
 * A plain function of a Context and an id — no service state — so it sits beside the service rather
 * than inside it. A null id is the session's standing activity, set before anything is playing.
 */
private fun Context.sessionActivity(youtubeId: String?): PendingIntent {
    val intent = Intent(this, MainActivity::class.java).apply {
        if (youtubeId != null) putExtra(PlaybackService.EXTRA_OPEN_PLAYER, youtubeId)
    }
    // Fixed request code: UPDATE_CURRENT swaps the extra in place of minting new intents.
    return PendingIntent.getActivity(
        this,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Fails fast, in debug only, when something reaches the player off its application looper.
 *
 * media3 1.11.0 made the session/controller state accessors throw from the wrong thread, where
 * void methods still auto-post — and [NowPlayingState.Transport.playPause] is on the reading side:
 * `Util.handlePlayPauseButtonAction` reads `playbackState` and `playWhenReady` to decide what a
 * play tap means on an ended video. Checking here rather than letting media3 throw puts the failure
 * at the call site, where the wrong thread actually entered.
 *
 * Every caller today is on the main thread, which is the service's and the player's application
 * looper (audited 2026-08-21: the bar's buttons come from Compose via MainActivity, and the one
 * application-scope launch in that file touches no player state). This is what keeps that true as
 * coroutines are added to it.
 *
 * Debug-only on purpose: a release build should not crash someone's playback over it, and media3's
 * own accessors already throw if it ever matters. Top-level rather than a member because it needs
 * nothing from the service but the flag, and the class is at detekt's function threshold.
 */
private fun ExoPlayer.checkOnApplicationLooper(isDebug: Boolean) {
    if (!isDebug) return
    check(Looper.myLooper() == applicationLooper) {
        "Player reached off its application looper from ${Thread.currentThread().name}"
    }
}

/**
 * Closes an open async trace section and returns the null its tracking field should now hold; a
 * null cookie is a section that was never open, and is a no-op. One implementation for every
 * closer — the frame that ends a measurement, the queue emptying under one, and the service dying
 * with one open — because a close path missed here is a slice running to the end of the capture.
 * Top-level for the same reason [checkOnApplicationLooper] is: the service class sits at detekt's
 * function threshold.
 */
private fun endSection(name: String, cookie: Int?): Int? {
    cookie?.let { SystemTrace.endAsyncSection(name, it) }
    return null
}
