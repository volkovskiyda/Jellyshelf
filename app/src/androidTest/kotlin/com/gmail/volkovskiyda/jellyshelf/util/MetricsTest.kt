package com.gmail.volkovskiyda.jellyshelf.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Metrics] writes one span to three sinks that disagree about almost everything, and the ways of
 * getting that wrong are quiet ones. A span reported when it should have been abandoned puts a
 * fabricated duration into the Firebase console; one abandoned when it should have been reported
 * loses the measurement entirely and looks identical from the app. A first-content span that
 * reports every emission instead of the first burns a device's whole Firebase budget during one
 * search session. None of those fail a build; all of them fail here.
 *
 * Instrumented rather than a JVM test for the same reason [com.gmail.volkovskiyda.jellyshelf.playback.IdleReconnectDataSourceTest]
 * is: the system-trace half of every span goes through `androidx.tracing`, which calls
 * `android.os.Trace`, and this project deliberately has no Robolectric. Nothing here needs a
 * server, a player or a device state — both sinks and the clock are fakes from [RecordingMetrics].
 */
@RunWith(AndroidJUnit4::class)
class MetricsTest {

    private val span = Span(section = "Jellyshelf.test.span", id = "test_span")

    @Test
    fun spanReportsTheBlockItWrapped() {
        val recorder = RecordingMetrics()

        val result = recorder.metrics.span(span) { trace ->
            recorder.elapsed = 25
            trace.attribute("result", "success")
            "value"
        }

        assertEquals("value", result)
        assertEquals(listOf("test_span"), recorder.reported())
        assertEquals(mapOf("result" to "success"), recorder.trace("test_span").attributes)
        assertEquals(listOf("app/test_span begin", "app/test_span end"), recorder.marks)
        assertEquals(listOf("test_span 25 ms"), recorder.logs)
    }

    @Test
    fun spanReportsWhenTheBlockThrows() {
        val recorder = RecordingMetrics()

        runCatching {
            recorder.metrics.span(span) {
                recorder.elapsed = 7
                error("sync failed")
            }
        }

        // The attribute the caller would have flipped on success is simply absent — the span still
        // reports, which is what makes a failed sync visible as a duration rather than as nothing.
        assertEquals(listOf("test_span"), recorder.reported())
        assertEquals(listOf("test_span 7 ms"), recorder.logs)
    }

    /**
     * The whole reason [Metrics.span] is inline. `tracedSync` wraps a block that returns out of
     * the enclosing function on an early failure, and the span must still close on the way past.
     * This failing to compile is as much of a failure as this failing to pass.
     */
    @Test
    fun spanLetsTheBlockReturnOutOfItsCaller() {
        val recorder = RecordingMetrics()

        assertEquals("early", earlyExit(recorder, bail = true))
        assertEquals("late", earlyExit(recorder, bail = false))
        assertEquals(listOf("test_span", "test_span"), recorder.reported())
    }

    private fun earlyExit(recorder: RecordingMetrics, bail: Boolean): String {
        recorder.metrics.span(span) { trace ->
            if (bail) return "early"
            trace.attribute("bailed", "no")
        }
        return "late"
    }

    @Test
    fun beginAndEndCarryAttributesAndMetrics() {
        val recorder = RecordingMetrics()

        val open = recorder.metrics.begin(span, track = "playback")
        recorder.elapsed = 1_450
        open.end(attributes = mapOf("source" to "hls"), metrics = mapOf("rows" to 12L))

        assertEquals(listOf("test_span"), recorder.reported())
        assertEquals(mapOf("source" to "hls"), recorder.trace("test_span").attributes)
        assertEquals(mapOf("rows" to 12L), recorder.trace("test_span").metrics)
        assertEquals(listOf("playback/test_span begin", "playback/test_span end"), recorder.marks)
        assertEquals(listOf("test_span 1450 ms"), recorder.logs)
    }

    @Test
    fun abandonLeavesTheTraceUnreported() {
        val recorder = RecordingMetrics()

        recorder.metrics.begin(span).abandon()

        // Started but never stopped: Firebase reports only stopped traces, which is how an
        // abandoned span disappears instead of reporting a duration nobody measured.
        assertEquals(1, recorder.traces.size)
        assertFalse(recorder.trace("test_span").stopped)
        assertEquals(emptyList<String>(), recorder.reported())
        assertEquals(listOf("app/test_span begin", "app/test_span abandoned"), recorder.marks)
        assertEquals(emptyList<String>(), recorder.logs)
    }

    @Test
    fun aSpanClosesOnce() {
        val recorder = RecordingMetrics()

        val open = recorder.metrics.begin(span)
        open.end()
        open.end()
        open.abandon()

        assertEquals(listOf("app/test_span begin", "app/test_span end"), recorder.marks)
        assertEquals(1, recorder.logs.size)
    }

    @Test
    fun anEndAfterAnAbandonDoesNotReport() {
        val recorder = RecordingMetrics()

        val open = recorder.metrics.begin(span)
        open.abandon()
        open.end()

        assertEquals(emptyList<String>(), recorder.reported())
        assertEquals(listOf("app/test_span begin", "app/test_span abandoned"), recorder.marks)
    }

    @Test
    fun suspendSpanTracesThroughKotzilla() = runTest {
        val recorder = RecordingMetrics()

        val result = recorder.metrics.suspendSpan(span) { trace ->
            trace.metric("items", 3)
            "resolved"
        }

        assertEquals("resolved", result)
        // A suspending block is a shape Kotzilla's own API can take whole, so this one is a real
        // trace there rather than the mark pair the other shapes leave.
        assertEquals(listOf("test_span"), recorder.suspendTraces)
        assertEquals(listOf("test_span"), recorder.reported())
        assertEquals(mapOf("items" to 3L), recorder.trace("test_span").metrics)
        assertEquals(emptyList<String>(), recorder.marks)
    }

    @Test
    fun firstContentMeasuresOnlyTheFirstEmission() = runTest {
        val recorder = RecordingMetrics()
        val source = flow {
            recorder.elapsed = 12
            emit(listOf("a", "b"))
            recorder.elapsed = 99
            emit(listOf("c"))
        }

        val seen = source.firstContent(recorder.metrics, span, track = "library") { it.size }.toList()

        assertEquals(listOf(listOf("a", "b"), listOf("c")), seen)
        assertEquals(1, recorder.traces.size)
        assertEquals(mapOf("rows" to 2L), recorder.trace("test_span").metrics)
        assertEquals(listOf("test_span 12 ms"), recorder.logs)
        assertEquals(listOf("library/test_span begin", "library/test_span end"), recorder.marks)
    }

    @Test
    fun firstContentAbandonsWhenNothingIsEmitted() = runTest {
        val recorder = RecordingMetrics()

        emptyFlow<List<String>>().firstContent(recorder.metrics, span) { it.size }.toList()

        assertEquals(emptyList<String>(), recorder.reported())
        assertTrue(recorder.marks.contains("app/test_span abandoned"))
    }

    @Test
    fun firstContentAbandonsACancelledCollection() = runTest {
        val recorder = RecordingMetrics()
        val collecting = CompletableDeferred<Unit>()
        val never = flow<List<String>> {
            collecting.complete(Unit)
            awaitCancellation()
        }

        val job = launch { never.firstContent(recorder.metrics, span) { it.size }.toList() }
        // The span opens before the upstream is collected, so this is past the begin.
        collecting.await()
        job.cancelAndJoin()

        // The query the next keystroke replaced: an abandoned mark, not a half-open trace.
        assertEquals(emptyList<String>(), recorder.reported())
        assertTrue(recorder.marks.contains("app/test_span abandoned"))
    }

    @Test
    fun firstContentMeasuresEveryCollectionSeparately() = runTest {
        val recorder = RecordingMetrics()
        val measured = flow { emit(listOf("a")) }.firstContent(recorder.metrics, span) { it.size }

        measured.toList()
        measured.toList()

        assertEquals(listOf("test_span", "test_span"), recorder.reported())
    }
}
