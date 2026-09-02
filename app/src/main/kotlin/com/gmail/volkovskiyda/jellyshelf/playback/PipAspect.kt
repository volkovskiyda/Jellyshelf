package com.gmail.volkovskiyda.jellyshelf.playback

/** A PiP aspect ratio as the platform will accept it — see [pipAspect]. */
data class PipAspect(val numerator: Int, val denominator: Int)

/**
 * The video's shape, clamped into the range `PictureInPictureParams` accepts.
 *
 * The platform rejects anything outside 1:2.39 … 2.39:1 by *throwing* from `setAspectRatio`, so
 * an unusually tall or wide video would crash the activity at the moment it tried to shrink.
 * Clamping keeps such a video in a legal window with black bars instead, which is what every
 * other player does with it.
 *
 * Null for a size that is not known yet (nothing has decoded, so there is nothing to shape the
 * window to) — the caller then leaves the ratio unset and lets the platform choose.
 */
fun pipAspect(width: Int, height: Int): PipAspect? {
    if (width <= 0 || height <= 0) return null
    return when {
        width > height * MAX_RATIO_NUMERATOR / MAX_RATIO_DENOMINATOR ->
            PipAspect(MAX_RATIO_NUMERATOR, MAX_RATIO_DENOMINATOR)

        height > width * MAX_RATIO_NUMERATOR / MAX_RATIO_DENOMINATOR ->
            PipAspect(MAX_RATIO_DENOMINATOR, MAX_RATIO_NUMERATOR)

        else -> PipAspect(width, height)
    }
}

/** The platform's limit, 2.39:1, as the integer pair the comparisons above use. */
private const val MAX_RATIO_NUMERATOR = 239
private const val MAX_RATIO_DENOMINATOR = 100
