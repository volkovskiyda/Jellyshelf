package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LongBufferingRule] decides when a buffering spell becomes a Crashlytics report. Too eager and
 * every slow start on mobile data is an event; a paused player counted as waiting reports every
 * video left paused mid-buffer; and without the once-per-item guard one stall is a report every
 * five seconds for as long as the spinner sits.
 */
class LongBufferingRuleTest {

    private val rule = LongBufferingRule(thresholdMs = THRESHOLD_MS)

    @Test
    fun reportsOnceTheWaitReachesTheThreshold() {
        assertFalse("too early", rule.shouldReport(THRESHOLD_MS - 1, playWhenReady = true))
        assertTrue(rule.shouldReport(THRESHOLD_MS, playWhenReady = true))
    }

    @Test
    fun aPausedPlayerKeepsNobodyWaiting() {
        assertFalse(rule.shouldReport(THRESHOLD_MS * 100, playWhenReady = false))
        // Nothing was spent: the first real wait still reports.
        assertTrue(rule.shouldReport(THRESHOLD_MS, playWhenReady = true))
    }

    @Test
    fun reportsAtMostOncePerItem() {
        assertTrue(rule.shouldReport(THRESHOLD_MS, playWhenReady = true))
        assertFalse("already reported", rule.shouldReport(THRESHOLD_MS * 2, playWhenReady = true))

        rule.onItemChanged()
        assertTrue("a new item is a new report", rule.shouldReport(THRESHOLD_MS, playWhenReady = true))
    }

    private companion object {
        const val THRESHOLD_MS = 30_000L
    }
}
