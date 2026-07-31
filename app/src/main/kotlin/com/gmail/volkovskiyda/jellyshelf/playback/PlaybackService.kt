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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.authorizedImageUrl
import com.gmail.volkovskiyda.jellyshelf.util.ticksToMillis
import com.google.common.util.concurrent.ListenableFuture
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
 * the saved resume position.
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
 * Server reporting reuses [LibraryRepository.reportPlaybackStopped] — one report per stop,
 * exactly like the external-player flow — plus periodic local-only saves
 * ([LibraryRepository.savePlaybackPosition]) so process death can't lose the position.
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

    private var activeMediaId: String? = null
    private var lastPositionMs = 0L
    private var completionReported = false
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // A source per stream, reading the credential at creation time so a re-login between
        // plays is picked up without restarting the service.
        val dataSourceFactory = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(
                    mapOf(Playback.TOKEN_HEADER to settingsState.settings.value?.credential.orEmpty()),
                )
                .createDataSource()
        }
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
        player.addListener(TranscodeFallbackListener())
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
        val id = activeMediaId
        if (p != null && id != null && !completionReported) {
            repo.reportPlaybackStopped(id, p.currentPosition, completed = false)
        }
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
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val resolved = mediaItems.map { resolve(it) }
            val defaultStart =
                startIndex == C.INDEX_UNSET && startPositionMs == C.TIME_UNSET && resolved.isNotEmpty()
            if (defaultStart) {
                // The resume path: a bare setMediaItem(mediaId) starts from the locally saved
                // position. A controller that wants somewhere specific passes it explicitly.
                val resumeTicks = resolved.first().mediaId
                    .let { repo.observeVideo(it).first()?.playbackPositionTicks } ?: 0L
                MediaSession.MediaItemsWithStartPosition(resolved, 0, ticksToMillis(resumeTicks))
            } else {
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
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
        return item.buildUpon()
            .setUri(Playback.streamUrl(settings.serverUrl, jellyfinItemId, credential = null))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channel)
                    // Query-credentialed: the session's bitmap loader fetches it with no headers.
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
            val previous = activeMediaId
            if (previous != null && previous != mediaItem?.mediaId) {
                // Auto-advance means the previous video played to its end; any other reason is
                // a replacement mid-way (a new video picked from Detail).
                val completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                repo.reportPlaybackStopped(previous, lastPositionMs, completed)
            }
            activeMediaId = mediaItem?.mediaId
            lastPositionMs = 0L
            completionReported = false
            // A notification tap should reopen the player on whatever is playing now.
            session?.setSessionActivity(sessionActivity(mediaItem?.mediaId))
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The order between this and onMediaItemTransition is not part of the Player
            // contract, so anchor on media ids rather than on reasons: another item's position
            // must never clobber the active one's — replacing a playing video would otherwise
            // report position 0 for it and wipe its saved resume spot. Leaving the active item
            // captures its exact final position (better than the ≤10 s-stale periodic value);
            // movement within it (seeks) tracks the new side.
            val leavingActive = oldPosition.mediaItem?.mediaId == activeMediaId &&
                newPosition.mediaItem?.mediaId != activeMediaId
            if (leavingActive) {
                lastPositionMs = oldPosition.positionMs
            } else if (newPosition.mediaItem?.mediaId == activeMediaId) {
                lastPositionMs = newPosition.positionMs
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                completionReported = false
                startPeriodicSave()
            } else {
                saveJob?.cancel()
                // Save on a genuine pause (READY) so a swipe-away right after loses nothing.
                // Buffering isn't a pause; ended has its own full report.
                val id = activeMediaId
                val p = player
                if (id != null && p != null && p.playbackState == Player.STATE_READY) {
                    lastPositionMs = p.currentPosition
                    repo.savePlaybackPosition(id, p.currentPosition)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val id = activeMediaId ?: return
            val durationMs = player?.duration?.takeIf { it != C.TIME_UNSET } ?: lastPositionMs
            repo.reportPlaybackStopped(id, durationMs, completed = true)
            // Remembered so onDestroy doesn't file a second, contradicting partway report.
            completionReported = true
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
                val id = activeMediaId
                val positionMs = player?.currentPosition
                if (id != null && positionMs != null) {
                    lastPositionMs = positionMs
                    repo.savePlaybackPosition(id, positionMs)
                }
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
         * Deliberately asymmetric: skipping filler is the common case, re-hearing a line the rare
         * one. The player screen's buttons and its double-tap read these through media3's
         * seek-button states, so the two can never drift apart.
         */
        private const val SEEK_BACK_INCREMENT_MS = 10_000L
        private const val SEEK_FORWARD_INCREMENT_MS = 30_000L

        // Unrelated to the seek increments despite matching one of them today.
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
    }
}
