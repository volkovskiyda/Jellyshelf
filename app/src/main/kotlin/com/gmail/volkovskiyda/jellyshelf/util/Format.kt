package com.gmail.volkovskiyda.jellyshelf.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ticks are 100-nanosecond units (Jellyfin/.NET). 10,000,000 ticks == 1 second. */
const val TICKS_PER_SECOND = 10_000_000L

fun ticksToSeconds(ticks: Long): Long = ticks / TICKS_PER_SECOND

/** 10,000 ticks == 1 millisecond. External players report positions in milliseconds. */
private const val TICKS_PER_MILLI = TICKS_PER_SECOND / 1000
fun millisToTicks(millis: Long): Long = millis * TICKS_PER_MILLI
fun ticksToMillis(ticks: Long): Long = ticks / TICKS_PER_MILLI

/** Formats a duration in seconds as H:MM:SS or M:SS. */
@Suppress("MagicNumber") // 3600/60 are the hour/minute bases of the H:MM:SS shape, not named values
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

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Renders an epoch-millis timestamp in [zone] as "yyyy-MM-dd HH:mm", matching the date shape
 * [formatUploadDate] produces, or null when there is nothing to show.
 *
 * 0 is the never-synced/never-set sentinel used throughout the schema, and negatives can only be
 * corrupt storage — both render as null so callers omit the line rather than printing 1970.
 *
 * This is the absolute renderer, and the shape every date in the app takes; [syncTimeOf] decides
 * *when* a sync time is said absolutely and delegates here once it is.
 */
fun formatTimestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (epochMillis <= 0L) return null
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).format(TIMESTAMP_FORMAT)
}

/**
 * How a sync time should be said. A shape rather than a string, because the relative cases need
 * plurals and this file stays free of Android resources — the UI turns these into text.
 */
sealed interface SyncTime {
    /** Under a minute old, or ahead of the device clock. */
    data object JustNow : SyncTime

    data class Minutes(val minutes: Int) : SyncTime

    data class Hours(val hours: Int) : SyncTime

    /** Past the cutover: the exact stamp, as [formatTimestamp] renders it. */
    data class Absolute(val timestamp: String) : SyncTime
}

/**
 * A sync time relative to [now] for its first [RELATIVE_CUTOFF_MS], absolute at or beyond that;
 * null for the same 0/negative sentinels [formatTimestamp] omits.
 *
 * The cutover is what reconciles the two things a timestamp has to be. Under three hours "12
 * minutes ago" is the answer the user actually wants when judging staleness, and it cannot drift
 * far while a screen sits open; at or beyond three hours the exact stamp is both more useful and
 * immune to going stale as the screen ages. (Screens that show one still refresh `now` — see the
 * detail screen — so the relative half does not silently rot either.)
 *
 * A timestamp ahead of [now] — clock skew, or a server running fast — clamps to [SyncTime.JustNow]
 * rather than counting down.
 */
fun syncTimeOf(epochMillis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): SyncTime? {
    if (epochMillis <= 0L) return null
    val age = now - epochMillis
    return when {
        age < MILLIS_PER_MINUTE -> SyncTime.JustNow
        age < MILLIS_PER_HOUR -> SyncTime.Minutes((age / MILLIS_PER_MINUTE).toInt())
        age < RELATIVE_CUTOFF_MS -> SyncTime.Hours((age / MILLIS_PER_HOUR).toInt())
        // Null is impossible here — epochMillis > 0 was checked above — but the absolute renderer
        // owns the sentinel rule, so defer to it rather than restating it.
        else -> formatTimestamp(epochMillis, zone)?.let(SyncTime::Absolute)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE

/** Relative up to here, absolute from here on. */
private const val RELATIVE_CUTOFF_MS = 3 * MILLIS_PER_HOUR

/** Fraction 0f..1f of a video watched, for a progress bar. */
fun watchedFraction(positionTicks: Long, durationSeconds: Long): Float {
    if (durationSeconds <= 0) return 0f
    val posSeconds = ticksToSeconds(positionTicks).toFloat()
    return (posSeconds / durationSeconds).coerceIn(0f, 1f)
}
