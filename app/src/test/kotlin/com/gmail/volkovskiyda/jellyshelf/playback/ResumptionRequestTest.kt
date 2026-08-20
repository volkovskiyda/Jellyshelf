package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One tick is 100 ns, so a second is ten million of them — see `ticksToMillis`. */
private const val TICKS_PER_SECOND = 10_000_000L

/**
 * What the system's resumption surfaces are offered after the process is gone.
 *
 * Every case here is one the user only ever meets through a quick-settings tile or a headset
 * button, with no app on screen to explain itself — so the wrong answer is silent by nature: a
 * resumption that never appears, or one that restarts a video the user had finished.
 */
class ResumptionRequestTest {

    @Test
    fun aPositionedVideoResumesWhereItWasLeft() {
        val request = resumptionRequest(
            lastPlayedId = "aaaaaaaaaaa",
            videoExists = true,
            savedTicks = 90L * TICKS_PER_SECOND,
            played = false,
        )

        assertEquals(ResumptionRequest("aaaaaaaaaaa", 90_000L), request)
    }

    @Test
    fun anUnstartedVideoResumesFromTheBeginning() {
        val request = resumptionRequest(
            lastPlayedId = "aaaaaaaaaaa",
            videoExists = true,
            savedTicks = 0L,
            played = false,
        )

        assertEquals(ResumptionRequest("aaaaaaaaaaa", 0L), request)
    }

    /**
     * Completion clears the position, so a watched row should have nothing to resume from anyway —
     * this is the belt to that pair of braces. Resuming a finished video two seconds from its end
     * is a failure nobody sees until it is in front of them.
     */
    @Test
    fun aWatchedVideoRestartsRatherThanResuming() {
        val request = resumptionRequest(
            lastPlayedId = "aaaaaaaaaaa",
            videoExists = true,
            savedTicks = 3_600L * TICKS_PER_SECOND,
            played = true,
        )

        assertEquals(ResumptionRequest("aaaaaaaaaaa", 0L), request)
    }

    /** Nothing has played, or the last thing was explicitly stopped, which clears the key. */
    @Test
    fun noLastPlayedIdMeansNothingToResume() {
        assertNull(
            resumptionRequest(lastPlayedId = null, videoExists = false, savedTicks = 0L, played = false),
        )
    }

    /**
     * The id outlived the video: a re-scoped sync, or the file gone from the server. Offering it
     * would fail later and less legibly, inside the player, with no screen to say so.
     */
    @Test
    fun aVideoThatHasLeftTheLibraryMeansNothingToResume() {
        assertNull(
            resumptionRequest(
                lastPlayedId = "aaaaaaaaaaa",
                videoExists = false,
                savedTicks = 90L * TICKS_PER_SECOND,
                played = false,
            ),
        )
    }

    /** A negative position is not a thing the store should hold, but it must not become a seek. */
    @Test
    fun aNonsensePositionIsNotOfferedAsASeek() {
        val request = resumptionRequest(
            lastPlayedId = "aaaaaaaaaaa",
            videoExists = true,
            savedTicks = -1L * TICKS_PER_SECOND,
            played = false,
        )

        assertEquals(ResumptionRequest("aaaaaaaaaaa", 0L), request)
    }
}
