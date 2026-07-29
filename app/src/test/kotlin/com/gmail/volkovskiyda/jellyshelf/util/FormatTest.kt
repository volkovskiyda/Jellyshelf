package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class FormatTest {

    @Test
    fun `tick conversions truncate to whole seconds and round-trip millis`() {
        assertEquals(1L, ticksToSeconds(10_000_000L))
        assertEquals(0L, ticksToSeconds(9_999_999L))
        assertEquals(10_000L, millisToTicks(1))
        assertEquals(1L, ticksToMillis(10_000L))
        assertEquals(1_500L, ticksToMillis(millisToTicks(1_500L)))
    }

    @Test
    fun `formatDuration renders minutes and hours`() {
        assertEquals("--:--", formatDuration(0))
        assertEquals("--:--", formatDuration(-5))
        assertEquals("0:59", formatDuration(59))
        assertEquals("1:00", formatDuration(60))
        assertEquals("59:59", formatDuration(3599))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("2:05:07", formatDuration(2 * 3600 + 5 * 60 + 7))
    }

    @Test
    fun `formatUploadDate hyphenates a full yyyymmdd date`() {
        assertEquals("2023-07-21", formatUploadDate("20230721"))
    }

    @Test
    fun `formatUploadDate passes a bare production year through`() {
        assertEquals("2023", formatUploadDate("2023"))
    }

    @Test
    fun `formatUploadDate rejects malformed input`() {
        assertNull(formatUploadDate(null))
        assertNull(formatUploadDate("notadate"))
        assertNull(formatUploadDate("2023-07-21"))
        assertNull(formatUploadDate("202307"))
        assertNull(formatUploadDate(""))
    }

    @Test
    fun `yearOf accepts yyyymmdd and bare years, rejects garbage`() {
        assertEquals("2023", yearOf("20230721"))
        assertEquals("2023", yearOf("2023"))
        assertNull(yearOf("20a3"))
        assertNull(yearOf("23"))
        assertNull(yearOf(null))
    }

    @Test
    fun `yearMonthOf validates the month`() {
        assertEquals("2023-07", yearMonthOf("20230721"))
        assertEquals("2023-12", yearMonthOf("202312"))
        assertNull(yearMonthOf("202300"))
        assertNull(yearMonthOf("202313"))
        assertNull(yearMonthOf("2023"))
        assertNull(yearMonthOf(null))
    }

    /** Pinned to a fixed zone: the default one would make the expectation machine-dependent. */
    private val utc = ZoneId.of("UTC")

    @Test
    fun `formatTimestamp renders epoch millis in the given zone`() {
        assertEquals("2026-07-25 10:15", formatTimestamp(1_784_974_530_000L, utc))
        // Same instant, +02:00 in July — the zone is applied, not ignored.
        assertEquals(
            "2026-07-25 12:15",
            formatTimestamp(1_784_974_530_000L, ZoneId.of("Europe/Berlin")),
        )
    }

    @Test
    fun `formatTimestamp treats zero and negatives as nothing to show`() {
        assertNull(formatTimestamp(0L, utc))
        assertNull(formatTimestamp(-1L, utc))
    }

    @Test
    fun `watchedFraction coerces into unit range and handles unknown duration`() {
        assertEquals(0f, watchedFraction(30 * TICKS_PER_SECOND, 0), 0f)
        assertEquals(0f, watchedFraction(0, 100), 0f)
        assertEquals(0.5f, watchedFraction(50 * TICKS_PER_SECOND, 100), 0.001f)
        assertEquals(1f, watchedFraction(200 * TICKS_PER_SECOND, 100), 0f)
        assertEquals(0f, watchedFraction(-10 * TICKS_PER_SECOND, 100), 0f)
    }
}
