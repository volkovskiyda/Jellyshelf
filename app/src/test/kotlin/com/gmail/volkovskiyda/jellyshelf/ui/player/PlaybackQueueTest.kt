package com.gmail.volkovskiyda.jellyshelf.ui.player

import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pure decisions behind the player's queue and chapter stepping: which list the session
 * is handed and where it starts, and where each chapter arrow goes from a given position.
 */
class PlaybackQueueTest {

    private val origin = listOf("aaa", "bbb", "ccc", "ddd")

    @Test
    fun `the queue is the whole origin list, positioned on the video that was opened`() {
        assertEquals(PlaybackQueue(origin, 2), playbackQueue(origin, "ccc"))
    }

    @Test
    fun `opening the first or last video still queues the whole list`() {
        assertEquals(PlaybackQueue(origin, 0), playbackQueue(origin, "aaa"))
        assertEquals(PlaybackQueue(origin, 3), playbackQueue(origin, "ddd"))
    }

    @Test
    fun `no origin means a single-item queue, exactly as before queues existed`() {
        assertEquals(PlaybackQueue(listOf("ccc"), 0), playbackQueue(emptyList(), "ccc"))
    }

    @Test
    fun `a video missing from the origin snapshot falls back to itself alone`() {
        // A stale snapshot — the list was taken before this video was synced into it. Queuing the
        // list without it would play something the user did not ask for.
        assertEquals(PlaybackQueue(listOf("zzz"), 0), playbackQueue(origin, "zzz"))
    }

    private val chapters = listOf(
        Chapter(0L, "Intro"),
        Chapter(120_000L, "Main part"),
        Chapter(600_000L, "Outro"),
    )

    @Test
    fun `previous restarts the current chapter once past the threshold`() {
        // 4 s into "Main part": back to its start, not to "Intro".
        assertEquals(120_000L, previousChapterStartMs(chapters, 124_000L))
    }

    @Test
    fun `previous steps back when barely into the current chapter`() {
        // 1 s in — still the "I meant the one before" window.
        assertEquals(0L, previousChapterStartMs(chapters, 121_000L))
    }

    @Test
    fun `the threshold itself counts as barely into the chapter`() {
        assertEquals(0L, previousChapterStartMs(chapters, 123_000L))
        assertEquals(120_000L, previousChapterStartMs(chapters, 123_001L))
    }

    @Test
    fun `previous has nowhere to go at the very start of the first chapter`() {
        assertNull(previousChapterStartMs(chapters, 0L))
        assertNull(previousChapterStartMs(chapters, 2_000L))
    }

    @Test
    fun `previous restarts the first chapter once past the threshold`() {
        assertEquals(0L, previousChapterStartMs(chapters, 30_000L))
    }

    @Test
    fun `previous does nothing before the first chapter begins`() {
        // Chapters that start late: there is no current chapter to restart or step back from.
        val late = listOf(Chapter(30_000L, "Late start"))
        assertNull(previousChapterStartMs(late, 10_000L))
    }

    @Test
    fun `next goes to the following chapter and stops at the last`() {
        assertEquals(120_000L, nextChapterStartMs(chapters, 0L))
        assertEquals(600_000L, nextChapterStartMs(chapters, 124_000L))
        assertNull(nextChapterStartMs(chapters, 600_000L))
        assertNull(nextChapterStartMs(chapters, 700_000L))
    }

    @Test
    fun `neither arrow does anything without chapters`() {
        assertNull(previousChapterStartMs(emptyList(), 10_000L))
        assertNull(nextChapterStartMs(emptyList(), 10_000L))
    }
}
