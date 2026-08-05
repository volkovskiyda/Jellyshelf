@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_ITEM_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlayMethod
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.util.Playback
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

    // The player's application thread is this service's main thread; session callbacks, the
    // listener and the periodic saver all stay on it, which is also what makes the plain-var
    // bookkeeping below safe.
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
        player.addListener(WatchStateListener())
        player.addListener(StartupTraceListener())
        player.addListener(TranscodeFallbackListener())
        // Last, so a transition has already been reported and re-tracked before this seeks.
        player.addListener(ResumeSeedingListener())
        this.player = player
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity(youtubeId = null))
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // The final stop report — fire-and-forget on the repository's own scope, so it survives
        // this service going away (swipe from recents, system stop).
        val p = player
        if (p != null) watch.onDestroy(p.currentPosition).perform()
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
            return scope.future {
                val resolved = mediaItems.map { resolve(it) }
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

    /** The same for a moment that decided several things at once, in the order the tracker chose. */
    private fun List<WatchAction>.perform() = forEach { it.perform() }

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
            if (!retrying) startupTrace = null
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

    private fun sessionActivity(youtubeId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            if (youtubeId != null) putExtra(EXTRA_OPEN_PLAYER, youtubeId)
        }
        // Fixed request code: UPDATE_CURRENT swaps the extra in place of minting new intents.
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
