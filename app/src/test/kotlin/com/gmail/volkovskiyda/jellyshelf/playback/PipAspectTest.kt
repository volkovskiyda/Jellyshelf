package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipAspectTest {

    @Test
    fun anOrdinaryVideoKeepsItsOwnShape() {
        assertEquals(PipAspect(1920, 1080), pipAspect(1920, 1080))
        assertEquals(PipAspect(1080, 1920), pipAspect(1080, 1920))
    }

    /** Nothing decoded yet: the platform picks, rather than being handed a degenerate ratio. */
    @Test
    fun anUnknownSizeHasNoAspect() {
        assertNull(pipAspect(0, 0))
        assertNull(pipAspect(1920, 0))
        assertNull(pipAspect(0, 1080))
        assertNull(pipAspect(-1920, -1080))
    }

    /**
     * The clamps are the point of the function: `setAspectRatio` *throws* outside 1:2.39 … 2.39:1,
     * so an ultra-wide film would crash the activity at the moment it tried to shrink.
     */
    @Test
    fun anUltraWideVideoIsClampedRatherThanRejected() {
        assertEquals(PipAspect(239, 100), pipAspect(4000, 1000))
    }

    @Test
    fun anUltraTallVideoIsClampedTheOtherWay() {
        assertEquals(PipAspect(100, 239), pipAspect(1000, 4000))
    }

    /** Right at the limit is legal, so it must survive unclamped. */
    @Test
    fun theLimitItselfIsNotClamped() {
        assertEquals(PipAspect(239, 100), pipAspect(239, 100))
        assertEquals(PipAspect(100, 239), pipAspect(100, 239))
    }
}
