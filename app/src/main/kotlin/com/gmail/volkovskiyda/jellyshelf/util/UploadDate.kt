package com.gmail.volkovskiyda.jellyshelf.util

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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

/** `YYYYMMDD_HHMMSS - ` at the very start of the name; the `_u`/`_e` date-only variants don't match. */
private val FILE_NAME_INSTANT = Regex("""^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2}) - """)

/**
 * The upload instant a library file name carries, in epoch seconds, or null when it carries none.
 *
 * The bot that downloads the library names every file through yt-dlp's output template
 * `%(timestamp>%Y%m%d_%H%M%S,upload_date>%Y%m%d_u,epoch>%Y%m%d_e)s - title [id].ext`, and yt-dlp
 * renders that `timestamp` in UTC — so the prefix is the very instant the feeds carry as
 * `uploadTimestamp`, to the second, available for every video the server holds whether or not
 * any feed has learned the field yet. The two date-only prefixes (`_u` when YouTube published
 * no instant, `_e` when the extraction day stood in for an unknown upload date) are not an
 * instant and must not become one, and a name without the prefix (demo rows, hand-renamed
 * files) is simply not stamped. An impossible calendar date in a matching prefix is treated the
 * same way rather than thrown on.
 */
@Suppress("MagicNumber") // the six capture groups of the prefix, not named values
fun uploadInstantFromFileName(fileName: String?): Long? {
    val match = FILE_NAME_INSTANT.find(fileName ?: return null) ?: return null
    val field = match.groupValues.drop(1).map(String::toInt)
    return runCatching {
        LocalDateTime.of(field[0], field[1], field[2], field[3], field[4], field[5]).toEpochSecond(ZoneOffset.UTC)
    }.getOrNull()
}

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
