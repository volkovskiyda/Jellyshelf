package com.gmail.volkovskiyda.jellyshelf.util

/** Ticks are 100-nanosecond units (Jellyfin/.NET). 10,000,000 ticks == 1 second. */
const val TICKS_PER_SECOND = 10_000_000L

fun secondsToTicks(seconds: Long): Long = seconds * TICKS_PER_SECOND
fun ticksToSeconds(ticks: Long): Long = ticks / TICKS_PER_SECOND

/** 10,000 ticks == 1 millisecond. External players report positions in milliseconds. */
private const val TICKS_PER_MILLI = TICKS_PER_SECOND / 1000
fun millisToTicks(millis: Long): Long = millis * TICKS_PER_MILLI
fun ticksToMillis(ticks: Long): Long = ticks / TICKS_PER_MILLI

/** Formats a duration in seconds as H:MM:SS or M:SS. */
fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "--:--"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * yt-dlp upload_date is "YYYYMMDD"; render as "YYYY-MM-DD". A bare "YYYY" (the Jellyfin
 * ProductionYear fallback) renders as the year. Anything else is malformed metadata and
 * renders as nothing, matching the validation contract of [yearOf]/[yearMonthOf] below.
 */
fun formatUploadDate(yyyymmdd: String?): String? {
    val d = yyyymmdd ?: return null
    if (!d.all { it.isDigit() }) return null
    return when (d.length) {
        4 -> d
        8 -> "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
        else -> null
    }
}

/**
 * The four-digit year from an upload-date string, or null if it has no leading year. Accepts
 * yt-dlp "YYYYMMDD" as well as the bare "YYYY" fallback stored from Jellyfin's ProductionYear.
 */
fun yearOf(uploadDate: String?): String? {
    val d = uploadDate ?: return null
    if (d.length < 4) return null
    val year = d.substring(0, 4)
    return if (year.all { it.isDigit() }) year else null
}

/** The "YYYY-MM" month from a "YYYYMMDD" upload date, or null if it lacks a month (bare year). */
fun yearMonthOf(uploadDate: String?): String? {
    val d = uploadDate ?: return null
    if (d.length < 6) return null
    val yearMonth = d.substring(0, 6)
    if (!yearMonth.all { it.isDigit() }) return null
    val month = yearMonth.substring(4, 6).toInt()
    if (month !in 1..12) return null
    return "${yearMonth.substring(0, 4)}-${yearMonth.substring(4, 6)}"
}

/** Fraction 0f..1f of a video watched, for a progress bar. */
fun watchedFraction(positionTicks: Long, durationSeconds: Long): Float {
    if (durationSeconds <= 0) return 0f
    val posSeconds = ticksToSeconds(positionTicks).toFloat()
    return (posSeconds / durationSeconds).coerceIn(0f, 1f)
}
