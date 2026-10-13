package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

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
    fun `formatUploadTime renders the instant in the given zone when there is one`() {
        // 2026-07-21 10:15:07 UTC
        val uploadTimestamp = 1_784_628_907L
        assertEquals("2026-07-21 10:15", formatUploadTime("20260721", uploadTimestamp, ZoneOffset.UTC))
        assertEquals("2026-07-21 13:15", formatUploadTime("20260721", uploadTimestamp, ZoneId.of("Europe/Kyiv")))
        // The date comes from the instant too, never glued onto upload_date's UTC calendar.
        assertEquals(
            "2026-07-21 03:15",
            formatUploadTime("20260721", uploadTimestamp, ZoneId.of("America/Los_Angeles")),
        )
        // 2026-07-21 23:15:07 UTC: upload_date still says the 21st, Tokyo is already on the 22nd.
        assertEquals("2026-07-22 08:15", formatUploadTime("20260721", 1_784_675_707L, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `formatUploadTime falls back to the bare date without an instant`() {
        assertEquals("2026-07-21", formatUploadTime("20260721", null, ZoneOffset.UTC))
        assertEquals("2026", formatUploadTime("2026", null, ZoneOffset.UTC))
        // The schema's 0/negative sentinels are "no instant", not 1970.
        assertEquals("2026-07-21", formatUploadTime("20260721", 0L, ZoneOffset.UTC))
        assertNull(formatUploadTime(null, null, ZoneOffset.UTC))
        assertNull(formatUploadTime("garbage", null, ZoneOffset.UTC))
    }

    @Test
    fun `uploadInstantFromFileName reads the bot's UTC prefix to the second`() {
        // Real library names; the sidecars of these files carry exactly these `timestamp`s.
        assertEquals(
            1_791_576_036L, // 2026-10-09T20:00:36Z
            uploadInstantFromFileName(
                "20261009_200036 - Qualcomm made a Pi 5 killer. Good luck getting one. [KUUuxkICOLw].mp4",
            ),
        )
        assertEquals(1_787_490_012L, uploadInstantFromFileName("20260823_130012 - Kotlin ｜ Web [yzIoilQB02Y].mkv"))
        // The instant renders through formatUploadTime exactly like a feed-carried one would.
        val fromName = uploadInstantFromFileName("20261009_200036 - x [id].mp4")
        assertEquals("2026-10-09 23:00", formatUploadTime("20261009", fromName, ZoneId.of("Europe/Kyiv")))
    }

    @Test
    fun `uploadInstantFromFileName refuses the date-only prefixes and anything else`() {
        // yt-dlp's template falls back to `_u` (upload_date only) and `_e` (extraction day).
        assertNull(uploadInstantFromFileName("20260705_u - No instant on YouTube [abc].mp4"))
        assertNull(uploadInstantFromFileName("20260705_e - Not even a date [abc].mp4"))
        assertNull(uploadInstantFromFileName("Demo title.mp4"))
        assertNull(uploadInstantFromFileName("Title [dQw4w9WgXcQ].mp4"))
        assertNull(uploadInstantFromFileName(null))
        assertNull(uploadInstantFromFileName(""))
        // Only at the very start, only with the " - " separator, only a possible calendar date.
        assertNull(uploadInstantFromFileName(" 20261009_200036 - x.mp4"))
        assertNull(uploadInstantFromFileName("20261009_200036.mp4"))
        assertNull(uploadInstantFromFileName("20261009_200036 x.mp4"))
        assertNull(uploadInstantFromFileName("20261345_990000 - impossible.mp4"))
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

    // A fixed "now" and ages measured back from it — the function takes now as a parameter
    // precisely so these stay deterministic.
    private val now = 1_784_974_530_000L
    private fun aged(millis: Long) = syncTimeOf(now - millis, now, utc)

    @Test
    fun `syncTimeOf says just now under a minute`() {
        assertEquals(SyncTime.JustNow, aged(0L))
        assertEquals(SyncTime.JustNow, aged(59_999L))
    }

    @Test
    fun `syncTimeOf counts whole minutes, then whole hours`() {
        assertEquals(SyncTime.Minutes(1), aged(60_000L))
        assertEquals(SyncTime.Minutes(12), aged(12 * 60_000L))
        assertEquals(SyncTime.Minutes(59), aged(59 * 60_000L + 59_999L))
        assertEquals(SyncTime.Hours(1), aged(60 * 60_000L))
        assertEquals(SyncTime.Hours(2), aged(2 * 60 * 60_000L))
    }

    /** The case a later refactor breaks: asserted at exactly three hours and either side of it. */
    @Test
    fun `syncTimeOf switches to an exact stamp at three hours`() {
        val threeHours = 3 * 60 * 60_000L
        assertEquals(SyncTime.Hours(2), aged(threeHours - 1L))
        assertEquals(SyncTime.Absolute("2026-07-25 07:15"), aged(threeHours))
        assertEquals(SyncTime.Absolute("2026-07-25 07:15"), aged(threeHours + 1L))
    }

    @Test
    fun `syncTimeOf clamps a future timestamp instead of counting down`() {
        // Clock skew, or a server running ahead of the device: "in -3 minutes" is not a thing.
        assertEquals(SyncTime.JustNow, syncTimeOf(now + 3 * 60_000L, now, utc))
    }

    @Test
    fun `syncTimeOf treats zero and negatives as nothing to show`() {
        assertNull(syncTimeOf(0L, now, utc))
        assertNull(syncTimeOf(-1L, now, utc))
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
