@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Rebuilds the player's loading state when a buffering stream has gone completely silent, so a stall
 * ends in either playback or an error rather than in a spinner that stays up forever.
 *
 * **Why a spinner can be the end state without this.** `onPlayerError` fires only once ExoPlayer has
 * exhausted its retries, and a retry that manages *some* progress resets the error count that
 * governs them (`ProgressiveMediaPeriod.onLoadError` asks for a retry with
 * `resetErrorCount = madeProgress`). A stream that trickles, or one whose reads fail in a way that
 * keeps looking fresh, can therefore be retried indefinitely with nothing ever surfacing. The player
 * screen draws a progress indicator for `STATE_BUFFERING` and offers no way out of it but Stop.
 *
 * [IdleReconnectDataSource] removes the one cause of that which is understood — a socket that died
 * while nobody was reading it. This is the backstop for the rest: it does not care *why* nothing is
 * arriving, only that nothing is.
 *
 * **What recovery does.** `stop()` then `prepare()`. `stop()` keeps the playlist, the current item
 * and the current position — it masks the state to idle and throws the loading state away — and
 * leaves `playWhenReady` alone, so `prepare()` picks the same video up where it was and plays on. It
 * is the same move [PlaybackService.TranscodeFallbackListener] makes when it swaps a failed direct
 * play for a transcode, and it flashes the shutter the same way.
 *
 * **Why that is invisible to watch-state reporting**, which matters because four listeners sit on
 * this player and two of them report to Jellyfin:
 *  - `stop()` raises `onIsPlayingChanged(false)` with the player no longer `STATE_READY`, and
 *    `WatchStateTracker.onPaused` answers nothing at all unless the player was ready — so no report
 *    and no position save, and the paused keep-alive is not started either.
 *  - No item transition fires, since the item does not change: no stop report, no `lastPlayedVideoId`
 *    write, no resume seek on the way back in.
 *  - `prepare()` leads to `onIsPlayingChanged(true)`, which sends one playing progress report on the
 *    session already open for this video. Correct, and the only thing the server sees.
 *  - The startup trace and its system-trace sections were closed at the first frame, long before.
 *
 * **The demo clip is exempt.** A bundled asset has no socket to go quiet, so a demo clip that
 * buffers is doing something this cannot fix.
 *
 * Threading: everything except [transferListener] runs on the player's application looper, which is
 * the service's main thread. `onBytesTransferred` arrives on loader threads instead, so the one value
 * it writes crosses over through a [MutableStateFlow] rather than a plain field — the convention in
 * this project for a slot two threads share.
 */
internal class StallWatchdog(
    private val scope: CoroutineScope,
    /**
     * A lambda, not the player: the service's player is null between sessions, and a watchdog
     * holding the instance it was built with would re-prepare one that has been released.
     */
    private val player: () -> ExoPlayer?,
    private val rule: StallRule = StallRule(),
    /**
     * Told that a recovery happened, so it can be counted. Called on the application looper, right
     * after the log line and before the player is touched — a recovery that throws is still one
     * that was attempted.
     *
     * Declared before the clock for the same reason it is in [IdleReconnectDataSource]: a callback
     * added after a defaulted lambda silently captures any trailing lambda at a call site.
     */
    private val onRecover: () -> Unit = {},
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    /** Written from loader threads, read on the application looper. See the class KDoc. */
    private val lastBytesMs = MutableStateFlow(0L)

    /** Runs only while a stall could be in progress; see [reevaluate]. */
    private var job: Job? = null

    /**
     * Hand this to the datasource factory, so every byte the player loads — on any stream, for the
     * current item or the preloaded next one — counts as the stream being alive.
     *
     * Deliberately not narrowed to the current item. The question being answered is whether the
     * network is doing anything at all, and a player that is fetching *something* is not the failure
     * this watches for.
     */
    val transferListener: TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            lastBytesMs.value = elapsedRealtime()
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    override fun onPlaybackStateChanged(playbackState: Int) = reevaluate()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = reevaluate()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        rule.onItemChanged()
        reevaluate()
    }

    /**
     * Starts or stops watching, according to whether the player is currently waiting for data it
     * wants. Called from every state change that can alter that answer.
     *
     * The ticking job exists only while a stall is possible, so an idle or playing player costs
     * nothing. Cancelling and relaunching it on each change is what keeps at most one running.
     */
    private fun reevaluate() {
        val player = player() ?: return
        val stalling = player.playbackState == Player.STATE_BUFFERING &&
            player.playWhenReady &&
            !player.isPlayingDemoClip()
        if (stalling) rule.onBuffering(elapsedRealtime()) else rule.onNotBuffering()
        job?.cancel()
        if (!stalling) return
        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                // Fed in on this thread rather than read inside the rule, so the rule stays pure.
                rule.onBytes(lastBytesMs.value)
                if (rule.tick(elapsedRealtime())) {
                    recover()
                    return@launch
                }
            }
        }
    }

    private fun recover() {
        val player = player() ?: return
        Timber.tag(Playback.TAG).w(
            "stall: nothing loaded for ${STALL_TIMEOUT_MS / MILLIS_PER_SECOND}s while buffering " +
                "${player.currentMediaItem?.mediaId} at ${player.currentPosition}ms — re-preparing",
        )
        onRecover()
        player.stop()
        player.prepare()
    }
}

/**
 * Whether what is loaded is the bundled demo clip, which is read from the APK and has no connection
 * to lose. The same check [PlaybackService]'s two error listeners make, for the same reason.
 */
private fun ExoPlayer.isPlayingDemoClip(): Boolean =
    currentMediaItem?.localConfiguration?.uri?.scheme == DEMO_CLIP_SCHEME

/**
 * Media3's own scheme for an APK asset. Spelled out here rather than shared with `PlaybackService`'s
 * private constant of the same value: that one belongs to the resolve path that *builds* demo URIs,
 * and a test-visible coupling between the two files would be the wrong kind of reuse for four
 * characters.
 */
private const val DEMO_CLIP_SCHEME = "asset"

/**
 * How often the stall window is checked. One second: the decision it feeds has a fifteen-second
 * threshold, so this only has to be fine enough that the recovery is not noticeably late.
 */
private const val TICK_MS = 1_000L

private const val MILLIS_PER_SECOND = 1_000L
