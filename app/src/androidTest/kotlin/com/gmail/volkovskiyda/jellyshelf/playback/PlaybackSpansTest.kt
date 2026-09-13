package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.util.RecordingMetrics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaybackSpans] decides which wait a rendered frame ended, and which waits are dropped rather
 * than reported. Both decisions are invisible from the app and arrive in a console days later.
 *
 * The expensive mistakes: a span left open until some frame minutes away, which reports a
 * minutes-long startup and reads as a catastrophic regression; and a span reported when it should
 * have been dropped, which quietly folds a demo clip playing off local storage, or a startup the
 * user walked away from, into the distribution everything else is judged against.
 *
 * Instrumented for the reason [com.gmail.volkovskiyda.jellyshelf.util.MetricsTest] gives: the spans
 * write system-trace sections through `androidx.tracing`, which calls `android.os.Trace`. Nothing
 * here needs a player — every callback is invoked by hand.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSpansTest {

    private val recorder = RecordingMetrics()
    private val spans = PlaybackSpans(recorder.metrics)

    @Test
    fun aStartupEndsAtTheFirstFrameWithItsSource() {
        spans.startupBegan(cookie = 1)
        recorder.elapsed = 1_450
        spans.firstFrame(source = PlaybackSpans.SOURCE_HLS, demo = false)

        assertEquals(listOf("player_startup"), recorder.reported())
        assertEquals(mapOf("source" to "hls"), recorder.trace("player_startup").attributes)
        assertEquals(listOf("player_startup 1450 ms"), recorder.logs)
    }

    @Test
    fun aDemoStartupIsDroppedRatherThanReported() {
        spans.startupBegan(cookie = 1)
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = true)

        // The bundled clip plays off local storage; reporting it would flatter every startup trend.
        assertEquals(emptyList<String>(), recorder.reported())
        assertTrue(recorder.marks.contains("playback/player_startup abandoned"))
    }

    @Test
    fun aReplacedStartupIsDropped() {
        spans.startupBegan(cookie = 1)
        spans.startupBegan(cookie = 2)
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        // Two opened, one reported: the user gave up on the first and started something else.
        assertEquals(2, recorder.traces.size)
        assertEquals(listOf("player_startup"), recorder.reported())
    }

    @Test
    fun anAdvanceBeforeTheFirstFrameDropsTheStartupItInterrupted() {
        spans.startupBegan(cookie = 1)
        spans.transitionBegan(cookie = 2, reason = PlaybackSpans.REASON_AUTO)
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        assertEquals(listOf("player_transition"), recorder.reported())
        assertTrue(recorder.marks.contains("playback/player_startup abandoned"))
    }

    @Test
    fun aTransitionCarriesHowTheQueueGotThere() {
        spans.transitionBegan(cookie = 1, reason = PlaybackSpans.REASON_SEEK)
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        assertEquals(mapOf("reason" to "seek"), recorder.trace("player_transition").attributes)
    }

    @Test
    fun aSeekCarriesItsDirectionAndDistance() {
        spans.seekBegan(cookie = 1, distanceMs = -30_000)
        recorder.elapsed = 180
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        assertEquals(mapOf("direction" to "back"), recorder.trace("player_seek").attributes)
        assertEquals(mapOf("distance_ms" to 30_000L), recorder.trace("player_seek").metrics)
        assertEquals(listOf("player_seek 180 ms"), recorder.logs)
    }

    @Test
    fun aForwardSeekSaysSo() {
        spans.seekBegan(cookie = 1, distanceMs = 10_000)
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        assertEquals(mapOf("direction" to "forward"), recorder.trace("player_seek").attributes)
    }

    /**
     * The rule that keeps a span from being closed by a frame minutes later: one frame ends every
     * wait that was in progress, not the newest and not one by priority.
     */
    @Test
    fun oneFrameEndsEverythingThatWasWaiting() {
        spans.transitionBegan(cookie = 1, reason = PlaybackSpans.REASON_AUTO)
        spans.seekBegan(cookie = 2, distanceMs = 5_000)
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        // Listed in the order they were opened, which is the order the player produced them in.
        assertEquals(listOf("player_transition", "player_seek"), recorder.reported())

        // And a later frame closes nothing, rather than reopening or double-reporting either.
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)
        assertEquals(listOf("player_transition", "player_seek"), recorder.reported())
    }

    @Test
    fun abandoningReportsNothing() {
        spans.startupBegan(cookie = 1)
        spans.transitionBegan(cookie = 2, reason = PlaybackSpans.REASON_AUTO)
        spans.seekBegan(cookie = 3, distanceMs = 1_000)
        spans.abandonAll()
        spans.firstFrame(source = PlaybackSpans.SOURCE_DIRECT, demo = false)

        assertEquals(emptyList<String>(), recorder.reported())
        assertEquals(emptyList<String>(), recorder.logs)
    }

    @Test
    fun resolvingIsTimedAsOneBlock() = runTest {
        val items = spans.resolving { listOf("a", "b") }

        assertEquals(listOf("a", "b"), items)
        // The one shape Kotzilla can time whole, so it gets a real trace there rather than marks.
        assertEquals(listOf("player_resolve"), recorder.suspendTraces)
        assertEquals(listOf("player_resolve"), recorder.reported())
    }
}
