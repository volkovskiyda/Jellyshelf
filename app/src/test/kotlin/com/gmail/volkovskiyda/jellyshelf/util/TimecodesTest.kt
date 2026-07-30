package com.gmail.volkovskiyda.jellyshelf.util

import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the YouTube chapter rules in [parseTimecodes]. The failure mode being guarded against is
 * silent garbage: a description that merely *mentions* times ("meet at 12:30") must never
 * render as tappable chapters, so every rule that collapses the result to empty is pinned.
 */
class TimecodesTest {

    private val duration = 754L // 12:34, matching the sample video used elsewhere

    @Test
    fun `a classic chapter list parses with millisecond starts and clean titles`() {
        val description = """
            My video about things.

            0:00 Intro
            2:00 Main part
            10:00 Outro
        """.trimIndent()

        assertEquals(
            listOf(
                Chapter(0L, "Intro"),
                Chapter(120_000L, "Main part"),
                Chapter(600_000L, "Outro"),
            ),
            parseTimecodes(description, duration),
        )
    }

    @Test
    fun `h_mm_ss entries parse alongside m_ss ones`() {
        val description = """
            0:00 Start
            59:59 Almost an hour in
            1:00:30 Past the hour
        """.trimIndent()

        assertEquals(
            listOf(
                Chapter(0L, "Start"),
                Chapter(3_599_000L, "Almost an hour in"),
                Chapter(3_630_000L, "Past the hour"),
            ),
            parseTimecodes(description, durationSeconds = 4_000L),
        )
    }

    @Test
    fun `separator variants strip down to the bare title`() {
        val description = """
            0:00 - Intro
            [2:00] Main part
            10:00 — Outro
        """.trimIndent()

        assertEquals(
            listOf("Intro", "Main part", "Outro"),
            parseTimecodes(description, duration).map(Chapter::title),
        )
    }

    @Test
    fun `a description without timecodes yields nothing`() {
        assertEquals(
            emptyList<Chapter>(),
            parseTimecodes("Just prose. No chapters anywhere here.", duration),
        )
    }

    @Test
    fun `fewer than three entries yield nothing`() {
        assertEquals(
            emptyList<Chapter>(),
            parseTimecodes("0:00 Intro\n5:00 Outro", duration),
        )
    }

    @Test
    fun `a list not starting at zero yields nothing`() {
        assertEquals(
            emptyList<Chapter>(),
            parseTimecodes("0:30 Intro\n2:00 Middle\n10:00 Outro", duration),
        )
    }

    @Test
    fun `a non-ascending list yields nothing`() {
        assertEquals(
            emptyList<Chapter>(),
            parseTimecodes("0:00 Intro\n10:00 Outro\n2:00 Middle", duration),
        )
    }

    /** Times past the video's end are prose, not chapters — dropped before the count check. */
    @Test
    fun `timestamps past the duration are dropped`() {
        val description = """
            0:00 Intro
            2:00 Main part
            10:00 Outro
            55:00 A time from some other context
        """.trimIndent()

        assertEquals(
            listOf(0L, 120_000L, 600_000L),
            parseTimecodes(description, duration).map(Chapter::startMs),
        )
    }

    /** With the out-of-range entry gone, only two remain — and two is not a chapter list. */
    @Test
    fun `dropping out-of-range entries can collapse the list to nothing`() {
        assertEquals(
            emptyList<Chapter>(),
            parseTimecodes("0:00 Intro\n2:00 Middle\n55:00 Beyond", duration),
        )
    }

    @Test
    fun `null and blank descriptions yield nothing`() {
        assertEquals(emptyList<Chapter>(), parseTimecodes(null, duration))
        assertEquals(emptyList<Chapter>(), parseTimecodes("   ", duration))
    }

    /** An unknown duration (0) skips the range check instead of dropping everything. */
    @Test
    fun `an unknown duration keeps all entries`() {
        val description = "0:00 Intro\n2:00 Middle\n55:00 Late"

        assertEquals(3, parseTimecodes(description, durationSeconds = 0L).size)
    }

    /** ":75" seconds is not a time; the line is skipped, and the valid three still qualify. */
    @Test
    fun `impossible seconds do not count as timestamps`() {
        assertEquals(
            listOf("Intro", "Outro", "More"),
            parseTimecodes("0:00 Intro\n2:75 Broken\n10:00 Outro\n11:00 More", duration)
                .map(Chapter::title),
        )
    }

    // --- currentChapter ---

    @Test
    fun `the current chapter is the last one at or before the position`() {
        val chapters = listOf(Chapter(0L, "Intro"), Chapter(120_000L, "Main"), Chapter(600_000L, "Outro"))

        assertEquals("Intro", currentChapter(chapters, 0L)?.title)
        assertEquals("Intro", currentChapter(chapters, 119_999L)?.title)
        assertEquals("Main", currentChapter(chapters, 120_000L)?.title)
        assertEquals("Outro", currentChapter(chapters, 700_000L)?.title)
    }

    @Test
    fun `no chapters means no current chapter`() {
        assertNull(currentChapter(emptyList(), 10_000L))
    }
}
