package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.ui.geometry.Offset
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder arithmetic behind press-and-hold's swipe: where a hold starts, how far the finger
 * travels per rung, where it clamps, and what it reports twice (nothing, ever).
 *
 * Pure geometry over [Offset], so it runs on the JVM. The speeds themselves come from
 * [PlaybackSpeed.holdOptions] rather than being written out, so a change to the ladder shows up as
 * a failure here rather than as a test that quietly asserts the old shape.
 */
class HoldSpeedTrackerTest {

    private class RecordingHost : HoldSpeedTracker.Host {
        val speeds = mutableListOf<Float>()
        var ends = 0

        override fun onHoldSpeed(speed: Float) {
            speeds += speed
        }

        override fun onHoldEnd() {
            ends++
        }
    }

    // A round step, so the arithmetic in each test reads as "n steps" rather than as pixels.
    private val step = 100f
    private val press = Offset(500f, 400f)

    @Test
    fun `a hold with no movement forces the default once and says nothing more`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        tracker.move(press)
        tracker.move(press + Offset(4f, 30f))

        assertEquals(listOf(PlaybackSpeed.HOLD_DEFAULT), host.speeds)
        assertTrue(tracker.isHolding)
    }

    @Test
    fun `sliding right walks up the ladder and clamps at the top`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        // One rung at a time, then far past the end of the ladder.
        (1..6).forEach { tracker.move(press + Offset(it * step, 0f)) }

        assertEquals(listOf(2f, 2.5f, 3f, 4f, 5f), host.speeds)
        assertEquals(PlaybackSpeed.holdOptions.last(), host.speeds.last())
    }

    @Test
    fun `sliding left walks down the ladder and clamps at the bottom`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        (1..8).forEach { tracker.move(press - Offset(it * step, 0f)) }

        assertEquals(listOf(2f, 1.75f, 1.5f, 1.25f, 1f, 0.75f, 0.5f), host.speeds)
        assertEquals(PlaybackSpeed.holdOptions.first(), host.speeds.last())
    }

    /** Half a step is the boundary, so a finger resting just inside one must not change the rung. */
    @Test
    fun `travel short of half a step changes nothing`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        tracker.move(press + Offset(step * 0.49f, 0f))
        tracker.move(press - Offset(step * 0.49f, 0f))

        assertEquals(listOf(PlaybackSpeed.HOLD_DEFAULT), host.speeds)
    }

    /**
     * The reason travel is measured from the press rather than summed per event: a swipe out and
     * back has to come home to the speed it started at, not drift a rung away from it.
     */
    @Test
    fun `sliding back returns through the same speeds`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        tracker.move(press + Offset(step, 0f))
        tracker.move(press + Offset(2 * step, 0f))
        tracker.move(press + Offset(step, 0f))
        tracker.move(press)

        assertEquals(listOf(2f, 2.5f, 3f, 2.5f, 2f), host.speeds)
    }

    /** This tracker is fed every gesture's movement, not only a hold's. */
    @Test
    fun `movement before a hold is inert`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.move(press + Offset(3 * step, 0f))

        assertTrue(host.speeds.isEmpty())
        assertFalse(tracker.isHolding)
    }

    @Test
    fun `a release without a hold neither ends nor throws`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.end()

        assertEquals(0, host.ends)
    }

    @Test
    fun `lifting ends the hold exactly once and frees the seek path`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        tracker.end()
        tracker.end()

        assertEquals(1, host.ends)
        assertFalse(tracker.isHolding)
    }

    /** A second hold starts over at the default, wherever the last one's swipe left off. */
    @Test
    fun `the next hold starts from the default again`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, step)
        tracker.move(press + Offset(4 * step, 0f))
        tracker.end()
        host.speeds.clear()

        tracker.start(press, step)

        assertEquals(listOf(PlaybackSpeed.HOLD_DEFAULT), host.speeds)
    }

    /** Density is a real number from the screen; a nonsense step must not divide by zero. */
    @Test
    fun `a zero step does not blow up`() {
        val host = RecordingHost()
        val tracker = HoldSpeedTracker(host)

        tracker.start(press, 0f)
        tracker.move(press + Offset(10f, 0f))

        assertEquals(PlaybackSpeed.holdOptions.last(), host.speeds.last())
    }
}
