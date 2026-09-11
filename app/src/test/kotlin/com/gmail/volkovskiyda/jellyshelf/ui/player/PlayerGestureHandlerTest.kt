package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drag state machine behind the player's scrub gesture: axis lock, edge exclusion, seeding,
 * range mapping and clamping. Pure geometry over [Offset]/[IntSize], so it runs on the JVM.
 *
 * The vertical cases assert on *inaction*: volume and brightness drags were removed, and the axis
 * check that used to route between them is now only there to keep a vertical fling from scrubbing.
 */
class PlayerGestureHandlerTest {

    private class RecordingHost(
        var seekable: Boolean = true,
        private val seekStart: Long = 60_000L,
        private val seekDuration: Long = 600_000L,
    ) : PlayerGestureHandler.Host {
        val seekPreviews = mutableListOf<Pair<Long, Long>>()
        val seekCommits = mutableListOf<Long>()
        var ended = 0

        override fun canSeek() = seekable
        override fun seekStartMs() = seekStart
        override fun seekDurationMs() = seekDuration
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

    // Landscape-ish surface: a full-width horizontal drag travels 90 s, so 1 px of scrub = 90 ms.
    private val size = IntSize(1000, 900)

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
    fun `the scrub seeds from the host's position, not from where the finger went down`() {
        val host = RecordingHost(seekStart = 300_000L)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(100f, 450f), size)
        handler.onDrag(Offset(100f, 0f))

        assertEquals(listOf(309_000L to 9_000L), host.seekPreviews)
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
    fun `a decisively vertical drag does nothing at all`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        // Both halves of the surface: neither adjusts volume or brightness any more, and neither
        // may fall through to a scrub.
        handler.onDragStart(Offset(750f, 450f), size)
        handler.onDrag(Offset(0f, -200f))
        handler.onDragEnd()
        handler.onDragStart(Offset(200f, 450f), size)
        handler.onDrag(Offset(0f, 200f))
        handler.onDragEnd()

        assertTrue(host.seekPreviews.isEmpty())
        assertTrue(host.seekCommits.isEmpty())
        assertEquals(0, host.ended)
    }

    @Test
    fun `a drag locked vertical stays inert even once it arcs horizontally`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(-5f, 30f))
        handler.onDrag(Offset(300f, 0f))
        handler.onDragEnd()

        assertTrue(host.seekPreviews.isEmpty())
        assertTrue(host.seekCommits.isEmpty())
    }

    @Test
    fun `a decisively horizontal drag keeps scrubbing even when it arcs`() {
        val host = RecordingHost(seekStart = 60_000L)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(30f, -5f))
        handler.onDrag(Offset(0f, -200f))
        handler.onDragEnd()

        // The lock survives the arc; the vertical travel simply moves the target nowhere.
        assertEquals(listOf(62_700L to 2_700L, 62_700L to 2_700L), host.seekPreviews)
        assertEquals(listOf(62_700L), host.seekCommits)
    }

    @Test
    fun `a diagonal drag stays undecided until one axis dominates two-to-one`() {
        val host = RecordingHost(seekStart = 60_000L)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        // Neither axis is twice the other yet: nothing happens.
        handler.onDrag(Offset(12f, 10f))
        assertTrue(host.seekPreviews.isEmpty())
        // Cumulative travel is now decisively horizontal, and it is cumulative travel that maps.
        handler.onDrag(Offset(30f, 0f))

        assertEquals(listOf(63_780L to 3_780L), host.seekPreviews)
    }

    @Test
    fun `lifting the finger after a scrub notifies the end exactly once`() {
        val host = RecordingHost()
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(100f, 0f))
        handler.onDragEnd()

        assertEquals(1, host.ended)
    }

    /**
     * The seam press-and-hold's swipe rides on: [PlayerGestureHandler.Host.canSeek] is asked once,
     * when the drag locks, and a refusal there keeps the whole of that gesture off the seek path.
     * The screen refuses while a hold is in flight, so a finger that holds and then slides adjusts
     * the speed without also scrubbing — and it must stay that way for the rest of the gesture even
     * though the hold's own flag clears the moment the finger lifts.
     */
    @Test
    fun `a drag refused at lock time stays inert even once seeking is allowed again`() {
        val host = RecordingHost(seekable = false)
        val handler = PlayerGestureHandler(host)

        handler.onDragStart(Offset(500f, 450f), size)
        handler.onDrag(Offset(100f, 0f))
        host.seekable = true
        handler.onDrag(Offset(100f, 0f))
        handler.onDragEnd()

        assertTrue(host.seekPreviews.isEmpty())
        assertTrue(host.seekCommits.isEmpty())
        assertEquals(0, host.ended)
    }
}
