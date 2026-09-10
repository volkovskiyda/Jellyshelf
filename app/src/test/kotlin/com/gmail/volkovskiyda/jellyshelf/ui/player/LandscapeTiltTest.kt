package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.view.OrientationEventListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate that decides when the rotate button's landscape request is handed back to the
 * sensor. Extracted from [LandscapeRequest] precisely so this can be asserted off-device: releasing
 * on a wrong reading drops a playing video back to portrait, and releasing on none at all pins it
 * sideways for the rest of the session.
 */
class LandscapeTiltTest {

    /** A device lying flat reports this, and it is not an orientation at all. */
    @Test
    fun `an unknown reading is not landscape`() {
        assertFalse(isLandscapeTilt(OrientationEventListener.ORIENTATION_UNKNOWN))
    }

    @Test
    fun `both ways round count as landscape`() {
        listOf(90, 270).forEach { assertTrue("$it°", isLandscapeTilt(it)) }
    }

    /** Held slightly off square — still plainly sideways, and the reason for the slack. */
    @Test
    fun `a hand-held tilt still counts`() {
        listOf(60, 120, 240, 300).forEach { assertTrue("$it°", isLandscapeTilt(it)) }
    }

    /** Upright either way up, and the diagonals a phone passes through mid-turn. */
    @Test
    fun `upright and half-turned readings are not landscape`() {
        listOf(0, 180, 360, 45, 135, 225, 315).forEach { assertFalse("$it°", isLandscapeTilt(it)) }
    }
}
