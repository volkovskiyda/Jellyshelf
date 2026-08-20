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

/**
 * Whether a stop at [positionTicks] finished the video rather than left it partway.
 *
 * [completed] is the player's own verdict — ExoPlayer reaching the end, the queue auto-advancing,
 * an external player reporting completion — and is trusted outright. Failing that, stopping within
 * [COMPLETION_THRESHOLD_SECONDS] of the end counts too: players report their last position a beat
 * short of the exact runtime, and closing a video on its final seconds is not "come back to this".
 *
 * A video of unknown length ([durationSeconds] zero, metadata not fetched yet) can only be finished
 * by [completed] — there is no end for a position to be near.
 *
 * This is the app's own rule, and it stays deliberately end-only: for anything short of the end the
 * server's thresholds decide watched-ness from the positions the in-app player reports.
 */
internal fun isFinishedStop(completed: Boolean, positionTicks: Long, durationSeconds: Long): Boolean =
    completed || (
        durationSeconds > 0 &&
            ticksToSeconds(positionTicks) >= durationSeconds - COMPLETION_THRESHOLD_SECONDS
        )

/** Stopping within this many seconds of the end counts as a finished watch, not a resume point. */
private const val COMPLETION_THRESHOLD_SECONDS = 5L
