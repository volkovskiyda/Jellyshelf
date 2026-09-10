package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.domain.model.VideoScaleMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scale mode's two contracts: the cycle the button walks, and reading a stored value back.
 *
 * The cycle is asserted as a whole sequence rather than step by step because its *shape* is what
 * matters — three taps must return to where they started, or the button becomes a one-way trip into
 * a mode the user cannot leave.
 */
class VideoScaleModeTest {

    @Test
    fun `three taps return to the starting mode`() {
        val walk = generateSequence(VideoScaleMode.FIT) { it.next() }.take(4).toList()

        assertEquals(
            listOf(
                VideoScaleMode.FIT,
                VideoScaleMode.ZOOM,
                VideoScaleMode.STRETCH,
                VideoScaleMode.FIT,
            ),
            walk,
        )
    }

    @Test
    fun `every mode round-trips through storage`() {
        VideoScaleMode.entries.forEach { mode ->
            assertEquals(mode, VideoScaleMode.fromStorage(mode.storageValue))
        }
    }

    /** A fresh install, or a preferences file that failed to read. */
    @Test
    fun `nothing stored reads as fit`() {
        assertEquals(VideoScaleMode.FIT, VideoScaleMode.fromStorage(null))
    }

    /**
     * A value from a build that offered a mode this one does not, or a corrupt file. It must degrade
     * to the letterboxing the player did before the button existed — never to a stretched picture.
     */
    @Test
    fun `an unknown stored mode reads as fit`() {
        listOf("sideways", "", "FIT", "zoom ").forEach {
            assertEquals("'$it' should degrade", VideoScaleMode.FIT, VideoScaleMode.fromStorage(it))
        }
    }
}
