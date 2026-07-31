package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.Player
import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which queue moves start a video where it was left. The failure this pins is silent in both
 * directions: seeding too little loses a resume point (the video restarts, and the next periodic
 * save overwrites what it should have resumed to), seeding too much overrules a start position
 * another path deliberately chose.
 *
 * The transition reasons are media3 `int` constants, so this stays a plain JVM test.
 */
class ResumeSeedingTest {

    private val fiveMinutes = millisToTicks(300_000L)

    @Test
    fun `auto-advancing to a part-watched video resumes it`() {
        assertEquals(
            300_000L,
            resumeSeekMs(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, fiveMinutes),
        )
    }

    @Test
    fun `skipping to a part-watched video resumes it`() {
        // previous/next, which is seekToPreviousMediaItem/seekToNextMediaItem.
        assertEquals(
            300_000L,
            resumeSeekMs(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, fiveMinutes),
        )
    }

    /**
     * Setting a queue and the transcode fallback both arrive as PLAYLIST_CHANGED, and both pass
     * the position they want — the session callback's resume lookup, and "exactly where the
     * failed decode left off". Seeding here would overrule whichever one it was.
     */
    @Test
    fun `a queue being set or swapped keeps the position it was given`() {
        assertNull(resumeSeekMs(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, fiveMinutes))
    }

    @Test
    fun `a repeat plays from the start, not from where the last round ended`() {
        assertNull(resumeSeekMs(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, fiveMinutes))
    }

    @Test
    fun `a video with nothing saved is left alone rather than seeked to zero`() {
        assertNull(resumeSeekMs(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, 0L))
        // What a completed video looks like: onPlaybackStopped clears the position when it marks
        // the video played, so auto-advancing back onto it must start it from the beginning.
        assertNull(resumeSeekMs(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 0L))
    }
}
