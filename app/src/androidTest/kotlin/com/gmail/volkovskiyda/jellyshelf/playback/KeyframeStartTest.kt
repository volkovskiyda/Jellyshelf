package com.gmail.volkovskiyda.jellyshelf.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a video starts on the service's own [buildPlayer] when it is handed a position — the resume
 * a queue opens with — or is resumed by [resumeSeekTo], and that an ordinary seek still lands
 * exactly.
 *
 * The clip is the bundled demo video, with keyframes at 0, 3.8 and 5.7 s. Asked to start at 5 s, the
 * player should start on the 3.8 s keyframe rather than decode 1.2 s of frames to throw away; asked
 * to seek to 5 s once playing, it should land on 5 s.
 */
@RunWith(AndroidJUnit4::class)
class KeyframeStartTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() = onMain {
        val context = instrumentation.targetContext
        player = buildPlayer(context, DefaultDataSource.Factory(context))
    }

    @After
    fun tearDown() = onMain { player.release() }

    @Test
    fun aStartPosition_startsOnTheKeyframeBeforeIt() {
        onMain {
            player.setMediaItem(MediaItem.fromUri(SAMPLE), 5_000L)
            player.prepare()
        }
        awaitReady()
        var positionMs = 0L
        onMain { positionMs = player.currentPosition }
        assertEquals(KEYFRAME_MS.toDouble(), positionMs.toDouble(), TOLERANCE_MS)
    }

    @Test
    fun aStartPositionOnAKeyframe_staysThere() {
        onMain {
            player.setMediaItem(MediaItem.fromUri(SAMPLE), KEYFRAME_MS)
            player.prepare()
        }
        awaitReady()
        var positionMs = 0L
        onMain { positionMs = player.currentPosition }
        assertEquals(KEYFRAME_MS.toDouble(), positionMs.toDouble(), TOLERANCE_MS)
    }

    @Test
    fun aSeekOnceLoaded_landsExactlyWhereItWasAsked() {
        onMain {
            player.setMediaItem(MediaItem.fromUri(SAMPLE))
            player.prepare()
        }
        awaitReady()
        onMain { player.seekTo(5_000L) }
        awaitReady()
        var positionMs = 0L
        onMain { positionMs = player.currentPosition }
        assertEquals(5_000.0, positionMs.toDouble(), TOLERANCE_MS)
    }

    @Test
    fun aResumeSeekOnceLoaded_landsOnTheKeyframeBeforeIt_andTheNextSeekIsExactAgain() {
        onMain {
            player.setMediaItem(MediaItem.fromUri(SAMPLE))
            player.prepare()
        }
        awaitReady()
        onMain { player.resumeSeekTo(5_000L) }
        awaitReady()
        var positionMs = 0L
        onMain { positionMs = player.currentPosition }
        assertEquals(KEYFRAME_MS.toDouble(), positionMs.toDouble(), TOLERANCE_MS)

        onMain { player.seekTo(5_000L) }
        awaitReady()
        onMain { positionMs = player.currentPosition }
        assertEquals(5_000.0, positionMs.toDouble(), TOLERANCE_MS)
    }

    private fun awaitReady() {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var ready = false
        // A settle first: a seek is only queued on the playback thread when seekTo returns, and the
        // state still reads READY from before it until that thread has picked it up.
        SystemClock.sleep(POLL_MS)
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            onMain { ready = player.playbackState == Player.STATE_READY }
            if (!ready) SystemClock.sleep(POLL_MS)
        }
        assertTrue("the player never became ready", ready)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val SAMPLE = "asset:///demo/sample.mp4"
        const val KEYFRAME_MS = 3_800L

        // The keyframe's own timestamp sits on a frame boundary, not on a round number.
        const val TOLERANCE_MS = 50.0
        const val READY_TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
