package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drag state machine behind the player's surface gestures: axis lock, zone pick, seeding,
 * range mapping and clamping. Pure geometry over [Offset]/[IntSize], so it runs on the JVM.
 */
class PlayerGestureHandlerTest {

    private class RecordingHost(
        private val volume: Float = 0.5f,
        private val brightness: Float = 0.5f,
    ) : PlayerGestureHandler.Host {
        val volumeChanges = mutableListOf<Float>()
        val brightnessChanges = mutableListOf<Float>()
        var ended = 0

        override fun volumeFraction() = volume
        override fun brightnessFraction() = brightness
        override fun onVolumeChange(fraction: Float) {
            volumeChanges += fraction
        }
        override fun onBrightnessChange(fraction: Float) {
            brightnessChanges += fraction
        }
        override fun onGestureEnd() {
            ended++
        }
    }

    // Landscape-ish surface: full swipe range = 900 * 0.66 px of travel.
    private val size = IntSize(1000, 900)
    private val fullSwipePx = 900f * 0.66f

    @Test
    fun `upward drag on the right half raises the volume from its current level`() {
        val host = RecordingHost(volume = 0.5f)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(0f, -10f))

        assertEquals(1, host.volumeChanges.size)
        assertEquals(0.5f + 10f / fullSwipePx, host.volumeChanges.single(), 1e-4f)
        assertTrue(host.brightnessChanges.isEmpty())
    }

    @Test
    fun `downward drag on the left half lowers the brightness from its current level`() {
        val host = RecordingHost(brightness = 0.5f)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(200f, 450f), size)
        handler.onDrag(Offset(0f, 120f))

        assertEquals(0.5f - 120f / fullSwipePx, host.brightnessChanges.single(), 1e-4f)
        assertTrue(host.volumeChanges.isEmpty())
    }

    @Test
    fun `the seed comes from the host, not from an assumed midpoint`() {
        val host = RecordingHost(volume = 0.8f)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(0f, -10f))

        assertEquals(0.8f + 10f / fullSwipePx, host.volumeChanges.single(), 1e-4f)
    }

    @Test
    fun `a full-range sweep clamps at the ends instead of wrapping`() {
        val host = RecordingHost(volume = 0.5f)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(0f, -fullSwipePx))
        handler.onDrag(Offset(0f, -50f))

        assertEquals(listOf(1f, 1f), host.volumeChanges)
    }

    @Test
    fun `a decisively horizontal drag locks away and never becomes a volume change`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(30f, -5f))
        handler.onDrag(Offset(0f, -200f))
        handler.onDragEnd()

        assertTrue(host.volumeChanges.isEmpty())
        assertTrue(host.brightnessChanges.isEmpty())
        assertEquals(0, host.ended)
    }

    @Test
    fun `a diagonal drag stays undecided until one axis dominates two-to-one`() {
        val host = RecordingHost(volume = 0.5f)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        // Neither axis is twice the other yet: nothing changes.
        handler.onDrag(Offset(10f, -12f))
        assertTrue(host.volumeChanges.isEmpty())
        // Cumulative travel is now decisively vertical; only this call's delta applies.
        handler.onDrag(Offset(0f, -30f))

        assertEquals(0.5f + 30f / fullSwipePx, host.volumeChanges.single(), 1e-4f)
    }

    @Test
    fun `drags starting in the top or bottom edge strips are left to the system`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 20f), size)
        handler.onDrag(Offset(0f, 100f))
        handler.onDragStart(Offset(750f, 880f), size)
        handler.onDrag(Offset(0f, -100f))

        assertTrue(host.volumeChanges.isEmpty())
        assertTrue(host.brightnessChanges.isEmpty())
    }

    @Test
    fun `lifting the finger after an adjustment notifies the end exactly once`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(0f, -10f))
        handler.onDragEnd()

        assertEquals(1, host.ended)
    }
}
