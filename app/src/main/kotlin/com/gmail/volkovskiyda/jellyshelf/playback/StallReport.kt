@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.gmail.volkovskiyda.jellyshelf.util.CrashReporter

/**
 * A playback stall, reported as a non-fatal so a release install can say what happened.
 *
 * Crashlytics groups on the stack frame, so every report lands in one issue; the message tells
 * them apart — the trigger and the player's state at that moment — and the [PlaybackTrail] attached
 * as the event's log lines is the rest of the story.
 */
internal class PlaybackStallException(message: String) : RuntimeException(message)

/**
 * Tells Crashlytics about a stall, with the [PlaybackTrail] as the event's log.
 *
 * Two triggers, from two places, and both are reported rather than acted on:
 *  - [StallWatchdog] recovering: fifteen seconds of a buffering player receiving nothing. The
 *    recovery is the action; this is what makes it visible outside a debug build.
 *  - A player buffering with `playWhenReady` for [LONG_BUFFERING_MS] regardless of bytes — the shape
 *    the watchdog deliberately ignores (a trickle is not silence), and the shape a recovery that did
 *    not help ends in. [LongBufferingRule] decides; `BufferingLogger` feeds it.
 *
 * In debug a report is one more line in logcat: Crashlytics collection is off there, and the trail
 * already went to logcat line by line.
 */
internal class StallReporter(
    private val trail: PlaybackTrail,
    private val crashes: CrashReporter,
) {

    fun report(trigger: String, player: ExoPlayer) {
        val message = "$trigger: ${player.describe()}"
        trail.w("stall report: $message")
        crashes.report(PlaybackStallException(message), trail.snapshot())
    }

    companion object {
        const val TRIGGER_SILENT = "silent stream re-prepared"
        const val TRIGGER_LONG = "buffering too long"
    }
}

/**
 * When a buffering spell has gone on long enough to report — at most once per item, and only while
 * someone is actually waiting. Pure, like [StallRule], and for the same reason.
 */
internal class LongBufferingRule(private val thresholdMs: Long = LONG_BUFFERING_MS) {

    private var reportedThisItem = false

    /** The queue moved to another video: a new item is a new report. */
    fun onItemChanged() {
        reportedThisItem = false
    }

    /**
     * @param waitingMs how long the player has been buffering *while asked to play*: from the later
     *   of the buffering starting and `playWhenReady` turning true. A paused player buffering for an
     *   hour has kept nobody waiting.
     */
    fun shouldReport(waitingMs: Long, playWhenReady: Boolean): Boolean {
        if (reportedThisItem || !playWhenReady || waitingMs < thresholdMs) return false
        reportedThisItem = true
        return true
    }
}

/**
 * Thirty seconds: twice the watchdog's silence window. The count restarts with every buffering
 * spell, and a recovery (`stop()` then `prepare()`) begins a new one, so after a recovery this is
 * thirty seconds of the re-prepare not helping — reported once, with the recovery in the trail.
 */
internal const val LONG_BUFFERING_MS = 30_000L

/**
 * Everything a stalled player can be asked on its own thread. `tracks` is group count, which is
 * zero until the period is prepared; `placeholder` is the timeline window still carrying the unset
 * duration a progressive source reports before its index has been read — the two agree when things
 * are normal, and a prepared item on a placeholder window is worth seeing.
 */
internal fun ExoPlayer.describe(): String {
    val window = currentTimeline.takeIf { !it.isEmpty }
        ?.getWindow(currentMediaItemIndex, Timeline.Window())
    return "item=${currentMediaItem?.mediaId} index=$currentMediaItemIndex " +
        "state=${stateName(playbackState)} playWhenReady=$playWhenReady isLoading=$isLoading " +
        "pos=${currentPosition}ms buffered=${bufferedPosition}ms ahead=${totalBufferedDuration}ms " +
        "duration=${duration.takeIf { it != C.TIME_UNSET } ?: "unset"} " +
        "tracks=${currentTracks.groups.size} placeholder=${window?.isPlaceholder} " +
        "hasNext=${hasNextMediaItem()}"
}
