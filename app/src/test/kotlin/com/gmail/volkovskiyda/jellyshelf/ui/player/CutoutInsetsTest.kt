package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which rows of the player step around a display cutout, and by how much. The geometry is the
 * Pixel 10 Pro emulator's (2856 × 1280 in landscape, a 156 px cutout strip around a centred
 * punch-hole) plus a Pixel 5-style corner hole, each in the row's own coordinates.
 */
class CutoutInsetsTest {

    /** The top bar in landscape, camera on the left: the hole is mid-height, nowhere near it. */
    @Test
    fun `a mid-height hole leaves the top bar alone`() {
        val hole = Rect(left = 0f, top = 586f, right = 156f, bottom = 695f)
        assertEquals(CutoutInsets.None, cutoutInsets(listOf(hole), width = 2856, height = 168))
    }

    /** The seek bar sits at the bottom, placed at y = 950: the hole is above it, in its coordinates. */
    @Test
    fun `a mid-height hole leaves the seek bar alone`() {
        val hole = Rect(left = 2700f, top = -364f, right = 2856f, bottom = -255f)
        assertEquals(CutoutInsets.None, cutoutInsets(listOf(hole), width = 2856, height = 330))
    }

    /** A row tall enough to reach the hole — a panel — steps sideways by the hole's own reach. */
    @Test
    fun `a row that reaches the hole steps away from its edge`() {
        val left = Rect(left = 0f, top = 200f, right = 156f, bottom = 309f)
        assertEquals(CutoutInsets(156, 0, 0, 0), cutoutInsets(listOf(left), width = 2856, height = 700))

        val right = Rect(left = 2700f, top = 200f, right = 2856f, bottom = 309f)
        assertEquals(CutoutInsets(0, 0, 156, 0), cutoutInsets(listOf(right), width = 2856, height = 700))
    }

    /** Portrait: the hole is centred along the top edge, so the bar can only move down. */
    @Test
    fun `a hole along the top pushes the top bar down`() {
        val hole = Rect(left = 586f, top = 0f, right = 695f, bottom = 156f)
        assertEquals(CutoutInsets(0, 156, 0, 0), cutoutInsets(listOf(hole), width = 1280, height = 168))
    }

    /** A corner hole crosses two edges; the row goes sideways, where a full-width row has room. */
    @Test
    fun `a corner hole pushes the row sideways, not down`() {
        val hole = Rect(left = 0f, top = 0f, right = 130f, bottom = 120f)
        assertEquals(CutoutInsets(130, 0, 0, 0), cutoutInsets(listOf(hole), width = 2340, height = 168))
    }

    /** A pill narrower than the hole above it cannot dodge sideways at all. */
    @Test
    fun `a hole spanning the whole width pushes down`() {
        val hole = Rect(left = -20f, top = 0f, right = 160f, bottom = 156f)
        assertEquals(CutoutInsets(0, 156, 0, 0), cutoutInsets(listOf(hole), width = 140, height = 100))
    }

    /** Ruler values are floats; a hole reaching 155.2 px still needs the whole 156th pixel. */
    @Test
    fun `fractional bounds round outwards`() {
        val hole = Rect(left = 0.4f, top = 0f, right = 155.2f, bottom = 120f)
        assertEquals(CutoutInsets(156, 0, 0, 0), cutoutInsets(listOf(hole), width = 2856, height = 168))
    }

    /** A ruler with no value yet reads as NaN; it must cost nothing rather than poison the sum. */
    @Test
    fun `unknown bounds are ignored`() {
        val unknown = Rect(left = Float.NaN, top = Float.NaN, right = Float.NaN, bottom = Float.NaN)
        assertEquals(CutoutInsets.None, cutoutInsets(listOf(unknown), width = 2856, height = 168))
    }

    @Test
    fun `no cutout, no padding`() {
        assertEquals(CutoutInsets.None, cutoutInsets(emptyList(), width = 2856, height = 168))
    }
}
