package com.gmail.volkovskiyda.jellyshelf.playback

import com.gmail.volkovskiyda.jellyshelf.util.Playback
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The last few minutes of what the player did, kept in memory on every build type.
 *
 * **Why a buffer and not just logcat.** Release plants no Timber tree and R8 strips every Timber
 * call (`src/main/keepRules`), so a release install has nothing to say about a stall after the
 * fact. On 2026-10-27 a tablet's release build finished a video, advanced the queue, and sat on
 * the next one's spinner for nearly three minutes; the server had logged the finished video's stop
 * and nothing else, and the app had logged nothing at all. Every playback line that used to go to
 * logcat alone goes here as well, and [StallReporter] hands the buffer to Crashlytics when a stall
 * is detected, so the next one arrives with the loads, states and transitions that led up to it.
 *
 * The same lines in debug: each is also written to logcat under [Playback.TAG], so
 * `adb logcat -s Playback` reads as it always did.
 *
 * Bounded at [capacity] lines, oldest dropped first. One line per 1 MB load chunk is the busiest
 * source, which at a direct-played 1080p rate is a handful a minute, so the default holds roughly
 * the last five to ten minutes of playback — a whole stall and what preceded it. Wall-clock stamps
 * in local time rather than monotonic ones, so a line can be laid beside the server's log (UTC) and
 * the user's own account of when it happened.
 *
 * Thread-safe: analytics callbacks arrive on the player's application looper, but
 * [IdleReconnectDataSource] reports from a loader thread, and the snapshot a report takes may race
 * either. A lock rather than a `MutableStateFlow` because this is a queue, not a slot — the
 * project's StateFlow convention is for one shared value.
 */
internal class PlaybackTrail(
    private val capacity: Int = CAPACITY,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)
    private val zone: ZoneId = ZoneId.systemDefault()

    fun d(message: String) {
        record(message)
        Timber.tag(Playback.TAG).d(message)
    }

    fun i(message: String) {
        record(message)
        Timber.tag(Playback.TAG).i(message)
    }

    fun w(message: String) {
        record(message)
        Timber.tag(Playback.TAG).w(message)
    }

    /** The cause rides along as its `toString()` — class and message — which is what a report needs. */
    fun w(error: Throwable, message: String) {
        record("$message: $error")
        Timber.tag(Playback.TAG).w(error, message)
    }

    /** The lines so far, oldest first — a copy, so a report can hand it off while lines keep coming. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    private fun record(message: String) {
        val stamp = STAMP.format(Instant.ofEpochMilli(wallClock()).atZone(zone))
        synchronized(lock) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast("$stamp $message")
        }
    }

    private companion object {
        const val CAPACITY = 200
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
