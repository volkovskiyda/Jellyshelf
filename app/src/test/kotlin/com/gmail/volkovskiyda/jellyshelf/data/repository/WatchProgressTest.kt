package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules a stop is judged by: the bar a position has to clear before it is recorded as
 * somewhere to come back to, and whether the stop finished the video outright.
 *
 * Worth pinning because both mistakes are quiet. Set the resume bar too low and stepping through a
 * queue leaves a resume point on every video it passes — over the top of whatever the user had
 * actually earned. Set it too high and a genuine watch is thrown away and the video restarts from
 * the beginning. Get the end window wrong and a finished video comes back as "continue watching",
 * or one abandoned partway is marked played.
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

    // --- The other half: did this stop finish the video, or leave it partway? ---

    private fun stoppedAt(seconds: Long, ofMinutes: Long, completed: Boolean = false) =
        isFinishedStop(completed, millisToTicks(seconds * 1000), durationSeconds = ofMinutes * 60)

    @Test
    fun `the last few seconds count as the end`() {
        // Players report their final position a beat short of the exact runtime, so "the end" has
        // to be a window rather than a point — five seconds of one, exactly.
        assertTrue(stoppedAt(595, ofMinutes = 10))
        assertFalse(stoppedAt(594, ofMinutes = 10))
    }

    @Test
    fun `the player's own verdict is trusted wherever it stopped`() {
        // ExoPlayer reaching the end, a queue auto-advance, an external player reporting
        // completion: a video whose runtime metadata is wrong still counts as watched.
        assertTrue(stoppedAt(5, ofMinutes = 45, completed = true))
    }

    @Test
    fun `stopping short of the end is not finished, however far in`() {
        // Deliberately not a percentage: at 95% this rule still says "resume point", and the
        // server's own threshold — fed by the session reports — is what calls that watched.
        assertFalse(stoppedAt(570, ofMinutes = 10))
    }

    @Test
    fun `a video of unknown length can only finish by completing`() {
        // durationSeconds 0 — no end for a position to be near, so only the player's verdict counts.
        assertFalse(stoppedAt(3600, ofMinutes = 0))
        assertTrue(stoppedAt(3600, ofMinutes = 0, completed = true))
    }
}
