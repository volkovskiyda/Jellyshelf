package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PLAYING = "aaaaaaaaaaa"
private const val ANOTHER = "bbbbbbbbbbb"

/**
 * The rule behind the stop that a video selection fires, tested as a pure function because what
 * it drives is only observable with a server and a decoder: a wrong answer surfaces either as two
 * videos playing at once or as a session the user never asked to end.
 */
class PlaybackHandoverTest {

    private fun playing(youtubeId: String) =
        NowPlaying(youtubeId = youtubeId, title = "Title", artworkUri = null, isPlaying = true)

    @Test
    fun anotherVideo_endsWhatIsPlaying() {
        assertTrue(endsCurrentPlayback(playing(PLAYING), ANOTHER))
    }

    /** Reopening the player from the mini-player bar, which must not disturb the session. */
    @Test
    fun theVideoAlreadyPlaying_doesNot() {
        assertFalse(endsCurrentPlayback(playing(PLAYING), PLAYING))
    }

    /**
     * The same rule covers the paused case without naming it: a paused video is still a session
     * with a position to report, so selecting a different one has to end it too.
     */
    @Test
    fun anotherVideo_endsAPausedOneAsWell() {
        assertTrue(endsCurrentPlayback(playing(PLAYING).copy(isPlaying = false), ANOTHER))
    }

    @Test
    fun nothingPlaying_hasNothingToEnd() {
        assertFalse(endsCurrentPlayback(null, ANOTHER))
    }
}
