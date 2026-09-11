package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StallRule]'s decision, which is the whole of [StallWatchdog] that can be reasoned about without a
 * device, a server and a socket that has stopped answering.
 *
 * Two mistakes here are worth more than the rest put together, and neither shows up as a crash.
 * Firing on a slow stream turns a video that would have started into one that never does, because
 * the recovery throws away the buffering it was about to finish. Firing repeatedly turns a stall into
 * a loop that re-prepares the player every fifteen seconds for as long as the screen is open, and
 * which never reaches the error ExoPlayer would otherwise have raised.
 */
class StallRuleTest {

    private val rule = StallRule(timeoutMs = TIMEOUT_MS)

    @Test
    fun firesOnceTheSilenceReachesTheTimeout() {
        rule.onBuffering(nowMs = 0)

        assertFalse("too early", rule.tick(TIMEOUT_MS - 1))
        assertTrue("the window has elapsed with nothing arriving", rule.tick(TIMEOUT_MS))
    }

    @Test
    fun bytesArrivingPushTheDeadlineOut() {
        rule.onBuffering(nowMs = 0)
        rule.onBytes(nowMs = 10_000)

        // The window is measured from the last byte, not from when buffering began.
        assertFalse("bytes are still arriving — this is slow, not stuck", rule.tick(TIMEOUT_MS))
        assertFalse(rule.tick(10_000 + TIMEOUT_MS - 1))
        assertTrue(rule.tick(10_000 + TIMEOUT_MS))
    }

    @Test
    fun neverFiresWhenPlaybackIsNotWaitingForData() {
        // A player that is paused, ready or stopped is not stalled however long it sits there.
        assertFalse(rule.tick(LONG_AFTER))

        rule.onBuffering(nowMs = 0)
        rule.onNotBuffering()
        assertFalse("no longer buffering", rule.tick(LONG_AFTER))
    }

    @Test
    fun aBufferingRunThatEndsInTimeNeverFires() {
        rule.onBuffering(nowMs = 0)
        rule.onBytes(nowMs = 2_000)
        rule.onNotBuffering()

        assertFalse(rule.tick(TIMEOUT_MS))
        assertFalse(rule.tick(LONG_AFTER))
    }

    @Test
    fun repeatedBufferingSignalsDoNotPushTheDeadlineOut() {
        rule.onBuffering(nowMs = 0)
        // The player can report its way through several states that all still mean "waiting"; none
        // of them may restart the clock, or a stall that keeps twitching is never recognised.
        rule.onBuffering(nowMs = 5_000)
        rule.onBuffering(nowMs = 10_000)

        assertTrue(rule.tick(TIMEOUT_MS))
    }

    @Test
    fun firesOnlyOnceForTheSameItem() {
        rule.onBuffering(nowMs = 0)
        assertTrue(rule.tick(TIMEOUT_MS))

        // Still buffering, still silent, and deliberately left alone: one recovery per item, then
        // the outcome belongs to ExoPlayer's own retries.
        assertFalse("a second recovery would be the start of a loop", rule.tick(LONG_AFTER))
        rule.onBuffering(nowMs = LONG_AFTER)
        assertFalse(rule.tick(LONG_AFTER * 2))
    }

    @Test
    fun theBudgetReturnsWhenTheQueueMovesOn() {
        rule.onBuffering(nowMs = 0)
        assertTrue(rule.tick(TIMEOUT_MS))

        rule.onItemChanged()
        // A new video is a new problem, and gets its own single attempt.
        assertFalse("the transition cleared the stall, so nothing is pending", rule.tick(LONG_AFTER))
        rule.onBuffering(nowMs = LONG_AFTER)
        assertTrue(rule.tick(LONG_AFTER + TIMEOUT_MS))
    }

    @Test
    fun aTransitionAloneDoesNotArmTheRule() {
        rule.onItemChanged()

        assertFalse(rule.tick(LONG_AFTER))
    }

    @Test
    fun bytesFromBeforeTheStallDoNotDelayIt() {
        // The watchdog feeds in the last byte timestamp on every tick, including one from well
        // before this stall began; the window must still be measured from the stall.
        rule.onBytes(nowMs = 1_000)
        rule.onBuffering(nowMs = 60_000)

        assertFalse(rule.tick(60_000 + TIMEOUT_MS - 1))
        assertTrue(rule.tick(60_000 + TIMEOUT_MS))
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val LONG_AFTER = 600_000L
    }
}
