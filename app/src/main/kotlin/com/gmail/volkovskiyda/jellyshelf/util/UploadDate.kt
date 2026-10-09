package com.gmail.volkovskiyda.jellyshelf.util

import java.time.ZoneId

// The upload date as the feeds carry it and as the UI says it. yt-dlp emits `upload_date` as a
// "YYYYMMDD" string and, for most videos, the upload instant as `timestamp`; the auto year/month
// categories key off the string, the label prefers the instant.

/**
 * yt-dlp upload_date is "YYYYMMDD"; render as "YYYY-MM-DD". A bare "YYYY" (the Jellyfin
 * ProductionYear fallback) renders as the year. Anything else is malformed metadata and
 * renders as nothing, matching the validation contract of [yearOf]/[yearMonthOf] below.
 */
@Suppress("MagicNumber") // 4/6/8 are the "YYYYMMDD" substring offsets, not named values
fun formatUploadDate(yyyymmdd: String?): String? {
    val d = yyyymmdd ?: return null
    if (!d.all { it.isDigit() }) return null
    return when (d.length) {
        4 -> d
        8 -> "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
        else -> null
    }
}

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The upload moment as the UI shows it: "yyyy-MM-dd HH:mm" in [zone] when the feed carried
 * yt-dlp's `timestamp` ([uploadTimestamp], epoch seconds), else the bare date [formatUploadDate]
 * makes of [uploadDate] — YouTube only publishes a time of day for most videos, not all, and an
 * index built before the field existed carries none.
 *
 * With a timestamp the whole stamp, date included, is the local rendering of that instant rather
 * than `upload_date` with a time glued on: yt-dlp derives `upload_date` in UTC, so near midnight
 * the two disagree, and a date from one calendar beside a time from another would be wrong twice.
 * The year/month auto categories keep grouping by `upload_date` — a grouping key wants one
 * answer everywhere, a label wants the viewer's clock.
 */
fun formatUploadTime(uploadDate: String?, uploadTimestamp: Long?, zone: ZoneId = ZoneId.systemDefault()): String? =
    uploadTimestamp?.let { formatTimestamp(it * MILLIS_PER_SECOND, zone) } ?: formatUploadDate(uploadDate)

/**
 * The four-digit year from an upload-date string, or null if it has no leading year. Accepts
 * yt-dlp "YYYYMMDD" as well as the bare "YYYY" fallback stored from Jellyfin's ProductionYear.
 */
@Suppress("MagicNumber") // 4 is the "YYYY" length, not a named value
fun yearOf(uploadDate: String?): String? {
    val d = uploadDate ?: return null
    if (d.length < 4) return null
    val year = d.substring(0, 4)
    return if (year.all { it.isDigit() }) year else null
}

/** The "YYYY-MM" month from a "YYYYMMDD" upload date, or null if it lacks a month (bare year). */
@Suppress("MagicNumber") // 4/6/12 are the "YYYYMM" substring offsets and month range, not named values
fun yearMonthOf(uploadDate: String?): String? {
    val d = uploadDate ?: return null
    if (d.length < 6) return null
    val yearMonth = d.substring(0, 6)
    if (!yearMonth.all { it.isDigit() }) return null
    val month = yearMonth.substring(4, 6).toInt()
    if (month !in 1..12) return null
    return "${yearMonth.substring(0, 4)}-${yearMonth.substring(4, 6)}"
}
