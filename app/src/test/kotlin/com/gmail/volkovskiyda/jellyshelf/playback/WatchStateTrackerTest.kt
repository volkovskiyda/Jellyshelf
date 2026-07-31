package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reporting rules the player screen and the server both depend on: one report per video,
 * `completed` only when it really finished, and a position that belongs to the video it is
 * reported against.
 *
 * These fail quietly rather than loudly — a wrong position is a resume spot silently moved, and a
 * duplicate report is a watch history that contradicts itself — which is exactly why they are
 * worth pinning here rather than leaving to a device session.
 */
class WatchStateTrackerTest {

    private val tracker = WatchStateTracker()

    /** Playing [id] with [positionMs] on the clock, the state every case below starts from. */
    private fun playing(id: String, positionMs: Long = 0L) {
        tracker.onItemChanged(newMediaId = id, autoAdvance = false)
        tracker.onPlaying()
        if (positionMs > 0L) tracker.onPeriodicSave(positionMs)
    }

    @Test
    fun `the first video starts without reporting anything`() {
        assertNull(tracker.onItemChanged("a", autoAdvance = false))
        assertEquals("a", tracker.activeMediaId)
    }

    @Test
    fun `a video that auto-advanced is reported complete`() {
        playing("a", positionMs = 700_000L)

        val report = tracker.onItemChanged("b", autoAdvance = true)

        assertEquals(WatchAction.Report("a", 700_000L, completed = true), report)
    }

    @Test
    fun `a video replaced mid-way is reported at its position, not as complete`() {
        playing("a", positionMs = 120_000L)

        val report = tracker.onItemChanged("b", autoAdvance = false)

        assertEquals(WatchAction.Report("a", 120_000L, completed = false), report)
    }

    @Test
    fun `the queue being cleared reports the video that was playing`() {
        // What leaving the player does: pause, then clear the queue.
        playing("a", positionMs = 90_000L)
        tracker.onPaused(positionMs = 95_000L, ready = true)

        val report = tracker.onItemChanged(null, autoAdvance = false)

        assertEquals(WatchAction.Report("a", 95_000L, completed = false), report)
        assertNull(tracker.activeMediaId)
    }

    @Test
    fun `re-selecting the video already playing reports nothing`() {
        playing("a", positionMs = 30_000L)

        assertNull(tracker.onItemChanged("a", autoAdvance = false))
    }

    @Test
    fun `a new video starts from zero, not from the last one's position`() {
        playing("a", positionMs = 300_000L)
        tracker.onItemChanged("b", autoAdvance = true)

        // Nothing has been observed about "b" yet, so a stop now is at its start.
        assertEquals(WatchAction.Report("b", 0L, completed = false), tracker.onDestroy(0L))
    }

    @Test
    fun `a pause while ready saves the position`() {
        playing("a")

        assertEquals(WatchAction.Save("a", 45_000L), tracker.onPaused(45_000L, ready = true))
    }

    @Test
    fun `buffering is not a pause and saves nothing`() {
        playing("a", positionMs = 45_000L)

        assertNull(tracker.onPaused(0L, ready = false))
        // And the buffering position did not overwrite what was known.
        assertEquals(45_000L, tracker.lastPositionMs)
    }

    @Test
    fun `an ended video is reported complete at its full duration`() {
        playing("a", positionMs = 700_000L)

        val report = tracker.onEnded(durationMs = 754_000L)

        assertEquals(WatchAction.Report("a", 754_000L, completed = true), report)
    }

    @Test
    fun `an ended video of unknown duration falls back to the last known position`() {
        playing("a", positionMs = 700_000L)

        assertEquals(
            WatchAction.Report("a", 700_000L, completed = true),
            tracker.onEnded(durationMs = null),
        )
    }

    @Test
    fun `a completed video is not reported a second time on the way out`() {
        // The duplicate this guards: onDestroy would otherwise file a partway report that
        // contradicts the completion already sent.
        playing("a", positionMs = 700_000L)
        tracker.onEnded(durationMs = 754_000L)

        assertNull(tracker.onDestroy(754_000L))
    }

    @Test
    fun `playing again after an ended video re-arms the final report`() {
        playing("a", positionMs = 700_000L)
        tracker.onEnded(durationMs = 754_000L)

        tracker.onPlaying()

        assertEquals(
            WatchAction.Report("a", 10_000L, completed = false),
            tracker.onDestroy(10_000L),
        )
    }

    @Test
    fun `swiping the app away reports the video partway`() {
        playing("a", positionMs = 120_000L)

        assertEquals(
            WatchAction.Report("a", 125_000L, completed = false),
            tracker.onDestroy(125_000L),
        )
    }

    @Test
    fun `with nothing playing there is nothing to report`() {
        assertNull(tracker.onDestroy(10_000L))
        assertNull(tracker.onEnded(754_000L))
        assertNull(tracker.onPeriodicSave(10_000L))
        assertNull(tracker.onPaused(10_000L, ready = true))
    }

    @Test
    fun `leaving the active item captures its own final position`() {
        playing("a", positionMs = 100_000L)

        // The exact position it left off at — better than the up-to-10s-stale periodic value.
        tracker.onPositionDiscontinuity("a", 123_456L, "b", 0L)

        assertEquals(123_456L, tracker.lastPositionMs)
    }

    @Test
    fun `a seek within the active item tracks the new position`() {
        playing("a", positionMs = 100_000L)

        tracker.onPositionDiscontinuity("a", 100_000L, "a", 250_000L)

        assertEquals(250_000L, tracker.lastPositionMs)
    }

    @Test
    fun `another item's position never clobbers the active one`() {
        // The bug this holds shut: the discontinuity for a *replacing* item can arrive before the
        // transition, and taking its position 0 would report the playing video at 0 — wiping the
        // resume spot the user had earned.
        playing("a", positionMs = 300_000L)

        tracker.onPositionDiscontinuity("b", 0L, "b", 0L)

        assertEquals(300_000L, tracker.lastPositionMs)
        assertEquals(
            WatchAction.Report("a", 300_000L, completed = false),
            tracker.onItemChanged("b", autoAdvance = false),
        )
    }
}
