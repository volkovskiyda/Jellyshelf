package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a persisted speed back. The round trip is the easy half; what matters is that every value
 * the menu cannot offer — absent, dropped in a later build, or corrupt — lands on 1× rather than on
 * a speed with no menu item to tick.
 */
class PlaybackSpeedTest {

    @Test
    fun `every menu option round-trips through storage`() {
        PlaybackSpeed.options.forEach { speed ->
            assertEquals(speed, PlaybackSpeed.fromStorage(speed), 0f)
        }
    }

    /** A fresh install, or a preferences file that failed to read. */
    @Test
    fun `nothing stored reads as normal speed`() {
        assertEquals(PlaybackSpeed.DEFAULT, PlaybackSpeed.fromStorage(null), 0f)
    }

    /** What a speed dropped from [PlaybackSpeed.options] in a later build leaves behind on disk. */
    @Test
    fun `a speed this build no longer offers reads as normal speed`() {
        assertEquals(PlaybackSpeed.DEFAULT, PlaybackSpeed.fromStorage(1.3f), 0f)
    }

    /**
     * Nonsense values, including the two that would silently break playback if they reached the
     * player. NaN gets there by a route worth naming: `NaN == NaN` is false, so it matches no
     * option and falls through the lookup rather than being rejected by any explicit check.
     */
    @Test
    fun `nonsense values read as normal speed`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals("$it should degrade", PlaybackSpeed.DEFAULT, PlaybackSpeed.fromStorage(it), 0f)
        }
    }
}
