package com.gmail.volkovskiyda.jellyshelf.util

import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter

// Pure JVM, like Playback.parsePlayerResult: the chapter decision stays host-side testable,
// and only the player UI touches Android.

/** The first `H:MM:SS` or `M:SS` in a line. `\b`s keep "123:45" and digit-runs from matching. */
private val TIMESTAMP = Regex("""\b(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b""")

/** Characters YouTube descriptions habitually wrap around a timestamp: "0:00 - Intro", "[0:00] Intro". */
private val TITLE_SEPARATORS = setOf('-', '–', '—', ':', '[', ']', '(', ')', '|', '·')

private const val MIN_CHAPTERS = 3
private const val MAX_MINUTES_OR_SECONDS = 59L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Parses YouTube-style chapter timecodes ("0:00 Intro" lines) out of a video [description].
 *
 * Mirrors YouTube's own chapter rules so prose that merely mentions times can't produce
 * garbage: at least [MIN_CHAPTERS] entries, the first at 0:00, strictly ascending, and all
 * within [durationSeconds] when it is known (> 0) — entries past the end are dropped before
 * the count check. Violating any rule returns an empty list: no chapters beats wrong chapters.
 */
fun parseTimecodes(description: String?, durationSeconds: Long): List<Chapter> {
    if (description.isNullOrBlank()) return emptyList()
    val parsed = description.lineSequence().mapNotNull(::parseChapterLine).toList()
    val entries = if (durationSeconds > 0) {
        parsed.filter { it.startMs <= durationSeconds * MILLIS_PER_SECOND }
    } else {
        parsed
    }
    val valid = entries.size >= MIN_CHAPTERS &&
        entries.first().startMs == 0L &&
        entries.zipWithNext().all { (previous, next) -> previous.startMs < next.startMs }
    return if (valid) entries else emptyList()
}

/** The chapter [positionMs] falls in — a chapter spans `[startMs, next.startMs)` — or null. */
fun currentChapter(chapters: List<Chapter>, positionMs: Long): Chapter? =
    chapters.lastOrNull { it.startMs <= positionMs }

/**
 * One line → one candidate chapter: the first timestamp in the line, titled with what remains
 * after the timestamp and its surrounding separators are stripped. Lines with no timestamp, an
 * impossible one (":75" seconds), or a blank title yield nothing.
 */
private fun parseChapterLine(line: String): Chapter? {
    val match = TIMESTAMP.find(line) ?: return null
    val (h, m, s) = match.destructured
    val hours = if (h.isEmpty()) 0L else h.toLong()
    val minutes = m.toLong()
    val seconds = s.toLong()
    if (seconds > MAX_MINUTES_OR_SECONDS) return null
    if (hours > 0 && minutes > MAX_MINUTES_OR_SECONDS) return null
    val startSeconds = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds
    val title = line.removeRange(match.range).trim { it.isWhitespace() || it in TITLE_SEPARATORS }
    if (title.isBlank()) return null
    return Chapter(startMs = startSeconds * MILLIS_PER_SECOND, title = title)
}
