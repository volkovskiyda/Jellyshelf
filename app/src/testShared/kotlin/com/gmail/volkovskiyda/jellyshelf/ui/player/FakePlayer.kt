package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player frozen at a fixed position, for the previews and behaviour tests that render
 * [PlayerControls] without a real one.
 *
 * media3's control layouts take a `Player` rather than plain values, so the position and duration
 * labels, the progress slider and the play/pause and seek button states all read off this instead
 * of off parameters. Everything it reports is constant, which is what keeps the goldens
 * deterministic: nothing here advances with wall-clock time.
 *
 * [SimpleBasePlayer] rather than a hand-written `Player` because the interface has some 90 members
 * and the base class derives all of them from one [getState] — and it ships in `media3-common`,
 * which the app already depends on, so no `media3-test-utils` is needed.
 *
 * Play/pause is the one thing that moves: media3's own play/pause state would otherwise never flip,
 * and a test that clicks the button asserts on what it does. Seeks are accepted and applied so the
 * slider's own internal seek has somewhere to land, but nothing plays.
 *
 * **It reports paused by default, and that is load-bearing rather than cosmetic.** media3's
 * progress polling (`ProgressStateJob.smartDelay`) waits out the media time to the next tick while
 * `isPlaying`, then re-reads — and a player whose position never advances never reaches that tick,
 * so the wait collapses to `withFrameMillis {}` and the poll runs every frame forever. Compose then
 * never goes idle and every test in the class dies with `ComposeNotIdleException`. Paused takes the
 * job's fixed-interval branch instead, which idles cleanly.
 */
@UnstableApi
class FakePlayer(
    private val durationMs: Long = DEFAULT_DURATION_MS,
    positionMs: Long = DEFAULT_POSITION_MS,
    playing: Boolean = false,
    private val itemCount: Int = 1,
) : SimpleBasePlayer(Util.getCurrentOrMainLooper()) {

    private var positionMs: Long = positionMs
    private var playWhenReady: Boolean = playing

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                )
                .build(),
        )
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaylist(
            List(itemCount) { index ->
                MediaItemData.Builder(index)
                    .setIsSeekable(true)
                    .setDurationUs(durationMs * C.MICROS_PER_SECOND / MILLIS_PER_SECOND)
                    .build()
            },
        )
        .setContentPositionMs(positionMs)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
    ): ListenableFuture<*> {
        this.positionMs = positionMs.coerceIn(0L, durationMs)
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** Ten minutes: long enough that the position label is well short of an hour. */
        const val DEFAULT_DURATION_MS = 600_000L

        /** Deliberately not zero — a zero position hides a label that formats zero specially. */
        const val DEFAULT_POSITION_MS = 10_000L
    }
}
