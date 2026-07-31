package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar a position has to clear before it is recorded as somewhere to come back to.
 *
 * Worth pinning because both mistakes are quiet. Set too low and stepping through a queue leaves
 * a resume point on every video it passes — over the top of whatever the user had actually
 * earned. Set too high and a genuine watch is thrown away and the video restarts from the
 * beginning.
 */
class WatchProgressTest {

    private fun at(seconds: Long, ofMinutes: Long) =
        isWorthResuming(millisToTicks(seconds * 1000), durationSeconds = ofMinutes * 60)

    @Test
    fun `a long video needs a minute, not a tenth of it`() {
        // A tenth of half an hour would be three minutes — far too much to ask before a watch
        // counts as started.
        assertFalse(at(59, ofMinutes = 30))
        assertTrue(at(61, ofMinutes = 30))
    }

    @Test
    fun `a short video needs a tenth of it, not a minute`() {
        // A flat minute would be a fifth of a five-minute video.
        assertFalse(at(29, ofMinutes = 5))
        assertTrue(at(31, ofMinutes = 5))
    }

    /**
     * The one place the two rules could disagree — a tenth of ten minutes *is* a minute, so they
     * don't. This is what makes the bar a single expression rather than a branch.
     */
    @Test
    fun `the two rules meet exactly at ten minutes`() {
        assertFalse(at(60, ofMinutes = 10))
        assertTrue(at(61, ofMinutes = 10))
        // And the bar moves continuously across that point rather than stepping: 54s at nine
        // minutes, 60s at ten, still 60s at eleven.
        assertFalse(at(54, ofMinutes = 9))
        assertTrue(at(55, ofMinutes = 9))
        assertFalse(at(60, ofMinutes = 11))
        assertTrue(at(61, ofMinutes = 11))
    }

    @Test
    fun `stepping past a video with previous or next records nothing`() {
        // The case this exists for: a few seconds of each video while skipping through a queue.
        listOf(2L, 5L, 30L).forEach { seconds ->
            assertFalse("${seconds}s in should not be a resume point", at(seconds, ofMinutes = 45))
        }
    }

    @Test
    fun `a video of unknown length counts from the very start`() {
        // durationSeconds 0 — metadata not fetched yet. Losing a real resume point would be the
        // worse mistake, so anything past the start clears the bar…
        assertTrue(at(1, ofMinutes = 0))
        // …but an exact zero never does, or it would overwrite a stored position with nothing.
        assertFalse(at(0, ofMinutes = 0))
    }

    @Test
    fun `position zero never clears the bar, whatever the duration`() {
        listOf(0L, 5L, 10L, 120L).forEach { minutes ->
            assertFalse("0s into ${minutes}m should not be a resume point", at(0, ofMinutes = minutes))
        }
    }
}
