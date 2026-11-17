package com.gmail.volkovskiyda.jellyshelf.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video track turned off and back on mid-play, on the service's own [buildPlayer] — what
 * `VideoOutputListener` does when the player is minimized and then reopened from the bar.
 *
 * The clip is the bundled demo video: ten seconds, fully buffered within moments of the start,
 * with keyframes at 0, 3.8 and 5.7 s. Video goes off at 1 s and comes back past 4.5 s, by which time
 * media3's default buffering would have thrown away everything the disabled track held before the
 * position, the keyframe at 3.8 s included. A clip that has finished loading has no reason to load
 * again, and a player that has every sample it needs has no reason to buffer, so either one after
 * video comes back is the full-stream reload that used to stop the audio on every reopen.
 */
@RunWith(AndroidJUnit4::class)
class VideoTrackReenableTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer

    // Touched on the main thread only, where the player calls its listeners.
    private val events = mutableListOf<String>()

    @Before
    fun setUp() = onMain {
        val context = instrumentation.targetContext
        player = buildPlayer(context, DefaultDataSource.Factory(context))
        // Without audio focus, which is not what this test is about. A test has no activity on
        // screen, and a platform that enforces audio hardening — API 35 does, API 36 only logs
        // what it would have done — refuses focus to such a process: the player then never starts
        // and the position stays at zero.
        player.setAudioAttributes(player.audioAttributes, false)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) events += "buffering"
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (isLoading) events += "loading"
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) events += "not playing"
            }
        })
        player.setMediaItem(MediaItem.fromUri("asset:///demo/sample.mp4"))
        player.prepare()
        player.play()
    }

    @After
    fun tearDown() = onMain { player.release() }

    @Test
    fun turningVideoBackOn_neitherReloadsNorStopsTheAudio() {
        awaitPosition(1_000)
        onMain { setVideo(enabled = false) }
        awaitPosition(4_500)
        var loading = true
        onMain { loading = player.isLoading }
        assertFalse("the clip should be fully loaded before video comes back", loading)

        onMain {
            events.clear()
            setVideo(enabled = true)
        }
        awaitPosition(6_500)

        onMain {
            assertEquals(emptyList<String>(), events)
            assertTrue(player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
        }
    }

    private fun setVideo(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    private fun awaitPosition(positionMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + positionMs + AWAIT_SLACK_MS
        var reached = false
        while (!reached && SystemClock.elapsedRealtime() < deadline) {
            onMain { reached = player.currentPosition >= positionMs }
            if (!reached) SystemClock.sleep(POLL_MS)
        }
        assertTrue("playback never reached $positionMs ms", reached)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val AWAIT_SLACK_MS = 5_000L
        const val POLL_MS = 20L
    }
}
