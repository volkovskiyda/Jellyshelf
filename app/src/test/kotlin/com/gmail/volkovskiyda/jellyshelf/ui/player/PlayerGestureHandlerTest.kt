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
        private val seekable: Boolean = true,
        private val seekStart: Long = 60_000L,
        private val seekDuration: Long = 600_000L,
    ) : PlayerGestureHandler.Host {
        val volumeChanges = mutableListOf<Float>()
        val brightnessChanges = mutableListOf<Float>()
        val seekPreviews = mutableListOf<Pair<Long, Long>>()
        val seekCommits = mutableListOf<Long>()
        var ended = 0

        override fun volumeFraction() = volume
        override fun brightnessFraction() = brightness
        override fun canSeek() = seekable
        override fun seekStartMs() = seekStart
        override fun seekDurationMs() = seekDuration
        override fun onVolumeChange(fraction: Float) {
            volumeChanges += fraction
        }
        override fun onBrightnessChange(fraction: Float) {
            brightnessChanges += fraction
        }
        override fun onSeekPreview(targetMs: Long, deltaMs: Long) {
            seekPreviews += targetMs to deltaMs
        }
        override fun onSeekCommit(targetMs: Long) {
            seekCommits += targetMs
        }
        override fun onGestureEnd() {
            ended++
        }
    }

    // Landscape-ish surface: full swipe range = 900 * 0.66 px of travel; a full-width
    // horizontal drag travels 90 s, so here 1 px of scrub = 90 ms.
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
    fun `a decisively horizontal drag never becomes a volume change, even when it arcs`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(30f, -5f))
        handler.onDrag(Offset(0f, -200f))
        handler.onDragEnd()

        assertTrue(host.volumeChanges.isEmpty())
        assertTrue(host.brightnessChanges.isEmpty())
    }

    @Test
    fun `a rightward drag scrubs forward proportionally to the width`() {
        val host = RecordingHost(seekStart = 60_000L, seekDuration = 600_000L)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(100f, 5f))
        handler.onDrag(Offset(50f, 0f))
        handler.onDragEnd()

        // 100 px = 9 s, then 150 px cumulative = 13.5 s past the 60 s start.
        assertEquals(listOf(69_000L to 9_000L, 73_500L to 13_500L), host.seekPreviews)
        assertEquals(listOf(73_500L), host.seekCommits)
        assertEquals(1, host.ended)
    }

    @Test
    fun `a leftward drag scrubs backward and clamps at the beginning`() {
        val host = RecordingHost(seekStart = 5_000L)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(-200f, 0f))
        handler.onDragEnd()

        // -200 px = -18 s: past the start of the video, so it lands on 0 with the real delta.
        assertEquals(listOf(0L to -5_000L), host.seekPreviews)
        assertEquals(listOf(0L), host.seekCommits)
    }

    @Test
    fun `a scrub clamps at the duration`() {
        val host = RecordingHost(seekStart = 590_000L, seekDuration = 600_000L)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(300f, 0f))
        handler.onDragEnd()

        assertEquals(listOf(600_000L to 10_000L), host.seekPreviews)
        assertEquals(listOf(600_000L), host.seekCommits)
    }

    @Test
    fun `horizontal drags do nothing while the item is not seekable`() {
        val host = RecordingHost(seekable = false)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(100f, 0f))
        handler.onDragEnd()

        assertTrue(host.seekPreviews.isEmpty())
        assertTrue(host.seekCommits.isEmpty())
        assertEquals(0, host.ended)
    }

    @Test
    fun `drags starting at the left or right screen edge are left to the system`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(20f, 450f), size)
        handler.onDrag(Offset(100f, 0f))
        handler.onDragStart(Offset(980f, 450f), size)
        handler.onDrag(Offset(-100f, 0f))

        assertTrue(host.seekPreviews.isEmpty())
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
