package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * Coarse duration ranges used both to group videos into duration categories at sync time and to
 * filter the library. [id] is a stable, sortable key (also used in the duration category id);
 * [label] is what the UI shows. Ranges are half-open, [minSeconds, maxSeconds). Videos with an
 * unknown duration (0 seconds) fall into no bucket — [minSeconds] of the first bucket is 1.
 */
@Suppress("MagicNumber") // the minute boundaries above are the bucket definitions themselves
enum class DurationBucket(val id: String, val label: String, val minSeconds: Long, val maxSeconds: Long) {
    UNDER_10("0", "0–10 min", 1, 10 * 60),
    FROM_10_TO_30("1", "10–30 min", 10 * 60, 30 * 60),
    FROM_30_TO_60("2", "30–60 min", 30 * 60, 60 * 60),
    OVER_60("3", "60+ min", 60 * 60, Long.MAX_VALUE);

    fun contains(seconds: Long): Boolean = seconds in minSeconds until maxSeconds

    companion object {
        /** The bucket [seconds] falls into, or null if the duration is unknown (<= 0). */
        fun of(seconds: Long): DurationBucket? = entries.firstOrNull { it.contains(seconds) }
    }
}
