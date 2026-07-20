package com.gmail.volkovskiyda.jellyshelf.util

/** Ticks are 100-nanosecond units (Jellyfin/.NET). 10,000,000 ticks == 1 second. */
const val TICKS_PER_SECOND = 10_000_000L

fun secondsToTicks(seconds: Long): Long = seconds * TICKS_PER_SECOND
fun ticksToSeconds(ticks: Long): Long = ticks / TICKS_PER_SECOND

/** Formats a duration in seconds as H:MM:SS or M:SS. */
fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "--:--"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** yt-dlp upload_date is "YYYYMMDD"; render as "YYYY-MM-DD". */
fun formatUploadDate(yyyymmdd: String?): String? {
    if (yyyymmdd == null || yyyymmdd.length != 8) return yyyymmdd
    return "${yyyymmdd.substring(0, 4)}-${yyyymmdd.substring(4, 6)}-${yyyymmdd.substring(6, 8)}"
}

/** Fraction 0f..1f of a video watched, for a progress bar. */
fun watchedFraction(positionTicks: Long, durationSeconds: Long): Float {
    if (durationSeconds <= 0) return 0f
    val posSeconds = ticksToSeconds(positionTicks).toFloat()
    return (posSeconds / durationSeconds).coerceIn(0f, 1f)
}
