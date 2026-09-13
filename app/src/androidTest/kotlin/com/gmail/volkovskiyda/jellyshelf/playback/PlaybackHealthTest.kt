package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.util.RecordingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaybackHealth] answers "how did that item go", and every way of getting it wrong produces a
 * believable number. Counting a seek's own refill as a rebuffer makes scrubbing look like a network
 * problem, on every device, forever. Counting the startup buffer makes every item begin with one
 * rebuffer. Failing to reset between items makes the second video inherit the first one's troubles.
 * None of that is visible from the app.
 *
 * Instrumented for the reason [com.gmail.volkovskiyda.jellyshelf.util.MetricsTest] gives: the trace
 * it opens writes a system-trace section through `androidx.tracing`. Nothing here needs a player —
 * every callback is invoked by hand, and the clock is [RecordingMetrics.elapsed].
 */
@RunWith(AndroidJUnit4::class)
class PlaybackHealthTest {

    private val recorder = RecordingMetrics()
    private val health = PlaybackHealth(recorder.metrics) { recorder.elapsed }

    private fun firstFrame(mediaId: String = "abc", demo: Boolean = false) =
        health.onFirstFrame(mediaId, source = PlaybackSpans.SOURCE_DIRECT, demo = demo)

    @Test
    fun anItemReportsWhatWentWrongWhileItPlayed() {
        firstFrame()
        recorder.elapsed = 5_000
        health.onBuffering()
        recorder.elapsed = 6_200
        health.onReady()
        health.onSeek()
        health.onIdleReconnect()
        health.onStallRecovery()
        recorder.elapsed = 60_000
        health.onItemEnded(PlaybackHealth.END_ENDED)

        assertEquals(listOf("player_item"), recorder.reported())
        assertEquals(
            mapOf(
                "rebuffers" to 1L,
                "rebuffer_ms" to 1_200L,
                "seeks" to 1L,
                "reconnects_idle" to 1L,
                "reconnects_stall" to 1L,
                "played_ms" to 60_000L,
            ),
            recorder.trace("player_item").metrics,
        )
        assertEquals(
            mapOf("source" to "direct", "end" to "ended"),
            recorder.trace("player_item").attributes,
        )
    }

    @Test
    fun bufferingBeforeTheFirstFrameIsTheStartupNotARebuffer() {
        health.onBuffering()
        health.onReady()
        firstFrame()
        health.onItemEnded(PlaybackHealth.END_ENDED)

        assertEquals(0L, recorder.trace("player_item").metrics["rebuffers"])
    }

    @Test
    fun theRefillAfterASeekIsNotARebuffer() {
        firstFrame()
        recorder.elapsed = 10_000
        health.onSeek()
        recorder.elapsed = 10_000 + PlaybackHealth.SEEK_SETTLE_MS - 1
        health.onBuffering()
        health.onReady()
        health.onItemEnded(PlaybackHealth.END_ENDED)

        // The player always reloads from the new position; counting it would make every scrub read
        // as a network problem.
        assertEquals(0L, recorder.trace("player_item").metrics["rebuffers"])
        assertEquals(1L, recorder.trace("player_item").metrics["seeks"])
    }

    @Test
    fun bufferingWellAfterASeekIsARebuffer() {
        firstFrame()
        recorder.elapsed = 10_000
        health.onSeek()
        recorder.elapsed = 10_000 + PlaybackHealth.SEEK_SETTLE_MS
        health.onBuffering()
        health.onReady()
        health.onItemEnded(PlaybackHealth.END_ENDED)

        assertEquals(1L, recorder.trace("player_item").metrics["rebuffers"])
    }

    @Test
    fun theDemoClipIsNeverReported() {
        firstFrame(demo = true)
        health.onBuffering()
        health.onItemEnded(PlaybackHealth.END_ENDED)

        // It plays off local storage and cannot stall for any reason a real stream can.
        assertEquals(emptyList<String>(), recorder.traces.map { it.id })
    }

    @Test
    fun theSecondItemDoesNotInheritTheFirstOnesTroubles() {
        firstFrame(mediaId = "one")
        recorder.elapsed = 5_000
        health.onBuffering()
        recorder.elapsed = 6_000
        health.onReady()
        health.onSeek()
        health.onItemEnded(PlaybackHealth.END_NEXT)

        recorder.elapsed = 7_000
        firstFrame(mediaId = "two")
        recorder.elapsed = 20_000
        health.onItemEnded(PlaybackHealth.END_ENDED)

        assertEquals(listOf("player_item", "player_item"), recorder.reported())
        val second = recorder.traces.last()
        assertEquals(0L, second.metrics["rebuffers"])
        assertEquals(0L, second.metrics["seeks"])
        assertEquals(13_000L, second.metrics["played_ms"])
        assertEquals("next", recorder.traces.first().attributes["end"])
    }

    @Test
    fun renderingTheSameItemAgainDoesNotStartOver() {
        firstFrame(mediaId = "one")
        health.onSeek()
        // Media3 renders a first frame again after every seek.
        firstFrame(mediaId = "one")
        health.onItemEnded(PlaybackHealth.END_ENDED)

        assertEquals(listOf("player_item"), recorder.reported())
        assertEquals(1L, recorder.trace("player_item").metrics["seeks"])
    }

    @Test
    fun anUnfinishedRebufferIsStillCountedWhenTheItemEnds() {
        firstFrame()
        recorder.elapsed = 1_000
        health.onBuffering()
        recorder.elapsed = 4_000
        health.onItemEnded(PlaybackHealth.END_STOPPED)

        assertEquals(1L, recorder.trace("player_item").metrics["rebuffers"])
        assertEquals(3_000L, recorder.trace("player_item").metrics["rebuffer_ms"])
    }

    @Test
    fun abandoningReportsNothing() {
        firstFrame()
        health.onIdleReconnect()
        health.onAbandoned()

        assertEquals(emptyList<String>(), recorder.reported())
    }

    @Test
    fun reconnectsCountFromALoaderThread() {
        firstFrame()
        val loader = Thread { repeat(5) { health.onIdleReconnect() } }
        loader.start()
        loader.join()
        health.onItemEnded(PlaybackHealth.END_ENDED)

        assertEquals(5L, recorder.trace("player_item").metrics["reconnects_idle"])
        assertTrue(recorder.marks.count { it == "playback/idle reconnect" } == 5)
    }
}
