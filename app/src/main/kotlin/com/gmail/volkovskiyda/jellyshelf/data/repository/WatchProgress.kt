package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds

/**
 * Whether a position is far enough into a video to be worth coming back to.
 *
 * Stepping through a queue touches every video it passes, and a resume point twenty seconds into
 * a half-hour video is worse than no resume point at all: the library row shows progress the user
 * never made, the player starts there instead of at the beginning, and — because a recorded
 * position overwrites whatever was there — it can destroy a resume point they actually earned.
 *
 * The bar is the smaller of a tenth of the video and one minute: proportional while a tenth is
 * real progress, flat once it isn't, so a feature-length video doesn't need ten minutes before it
 * counts as started. The two rules agree exactly at a ten-minute video — a tenth of it *is* a
 * minute — which is why this is one expression rather than a branch with a boundary to get wrong.
 *
 * A video of unknown length ([durationSeconds] zero, metadata not fetched yet) clears the bar at
 * any position past the very start: losing a real resume point is the worse mistake, and a
 * position of exactly zero must never overwrite a stored one.
 */
internal fun isWorthResuming(positionTicks: Long, durationSeconds: Long): Boolean =
    ticksToSeconds(positionTicks) > minOf(durationSeconds / PROGRESS_DIVISOR, MAX_PROGRESS_SECONDS)

/** A tenth of the video, for anything short enough that a flat minute would be most of it. */
private const val PROGRESS_DIVISOR = 10

/** …and never further in than this, however long the video runs. */
private const val MAX_PROGRESS_SECONDS = 60L
