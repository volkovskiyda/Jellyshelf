package com.gmail.volkovskiyda.jellyshelf.playback

import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind `setAutoEnterEnabled`. It is worth its own tests precisely because the
 * behaviour it drives is untestable: auto-enter happens inside the platform, on a gesture, with
 * no callback to assert on — so a wrong rule shows up as a feature that silently never fires, or
 * as a video window appearing over someone's home screen when they only wanted to leave.
 */
class PipEligibilityTest {

    private fun playing(isPlaying: Boolean = true, width: Int = 1920, height: Int = 1080) =
        NowPlaying(
            youtubeId = "aaaaaaaaaaa",
            title = "Title",
            artworkUri = null,
            isPlaying = isPlaying,
            videoWidth = width,
            videoHeight = height,
        )

    @Test
    fun thePlayerScreenPlaying_isEligible() {
        assertTrue(pipEligible(AppNavKey.Player("aaaaaaaaaaa"), playing()))
    }

    /** A paused video would shrink into a still frame the user then has to dismiss. */
    @Test
    fun thePlayerScreenPaused_isNot() {
        assertFalse(pipEligible(AppNavKey.Player("aaaaaaaaaaa"), playing(isPlaying = false)))
    }

    /**
     * The case the mini-player bar creates, and the reason the rule takes the screen into account
     * at all: playback survives leaving the player now, so "something is playing" on its own would
     * shrink the library into a video window every time someone went Home while listening.
     */
    @Test
    fun anotherScreenWhilePlaying_isNot() {
        assertFalse(pipEligible(AppNavKey.Library, playing()))
        assertFalse(pipEligible(AppNavKey.Settings, playing()))
        assertFalse(pipEligible(AppNavKey.Detail("aaaaaaaaaaa"), playing()))
    }

    @Test
    fun thePlayerScreenWithNothingPlaying_isNot() {
        assertFalse(pipEligible(AppNavKey.Player("aaaaaaaaaaa"), null))
    }

    @Test
    fun noScreenAtAll_isNot() {
        assertFalse(pipEligible(null, playing()))
    }

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
