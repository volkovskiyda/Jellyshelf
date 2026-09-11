package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The hold ladder is the menu's speeds with two skim speeds on the end — not a second,
     * independent list. A rung the menu also offers has to be the *same* rung, or a hold that
     * swipes to 1.5× and a menu pick of 1.5× would be two different speeds.
     */
    @Test
    fun `the hold ladder extends the menu rather than replacing it`() {
        assertEquals(PlaybackSpeed.options, PlaybackSpeed.holdOptions.take(PlaybackSpeed.options.size))
        assertEquals(listOf(4f, 5f), PlaybackSpeed.holdOptions.drop(PlaybackSpeed.options.size))
    }

    /** A hold starts mid-ladder, with rungs left in both directions for the swipe to reach. */
    @Test
    fun `the hold default sits inside the ladder with room either side`() {
        val index = PlaybackSpeed.holdOptions.indexOf(PlaybackSpeed.HOLD_DEFAULT)
        assertTrue("the hold default must be on the ladder", index >= 0)
        assertTrue("no room to swipe down", index > 0)
        assertTrue("no room to swipe up", index < PlaybackSpeed.holdOptions.lastIndex)
    }

    /**
     * The hold-only speeds must not survive a disk read. They are reachable only while a finger is
     * down, so one arriving from storage would mean a video left playing at 5× with a menu that
     * cannot show it — the same failure the whole [PlaybackSpeed.fromStorage] contract exists for.
     */
    @Test
    fun `a hold-only speed does not read back from storage`() {
        listOf(4f, 5f).forEach {
            assertEquals("$it is hold-only", PlaybackSpeed.DEFAULT, PlaybackSpeed.fromStorage(it), 0f)
        }
    }

    /**
     * Which rungs buzz. The landmarks are the round numbers; the steps between them are silent on
     * purpose, because ticking all eleven turns a slow swipe into a buzz.
     */
    @Test
    fun `only the round landmarks tick`() {
        assertTrue(PlaybackSpeed.holdOptions.containsAll(PlaybackSpeed.hapticLandmarks))
        assertEquals(setOf(0.5f, 1f, 2f, 3f, 4f, 5f), PlaybackSpeed.hapticLandmarks)
        listOf(0.75f, 1.25f, 1.5f, 1.75f, 2.5f).forEach {
            assertFalse("$it should be silent", it in PlaybackSpeed.hapticLandmarks)
        }
    }
}
