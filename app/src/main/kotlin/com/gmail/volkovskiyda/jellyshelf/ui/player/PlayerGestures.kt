package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The player surface's one drag gesture: a decisively horizontal drag scrubs. A full surface
 * width travels [SEEK_FULL_WIDTH_MS] through the video, the target is previewed while the finger
 * moves, and the seek itself fires on release — the Jellyfin app's behavior. Drags starting in
 * the edge strips are left to the system's own edge gestures.
 *
 * The [AXIS_LOCK_RATIO] dominance check is what remains of a wider arbitration: this surface once
 * also carried vertical volume and brightness drags, which were removed deliberately (volume has
 * hardware keys and the notification; brightness had no in-app affordance). What the check buys
 * now is that a vertical fling over the video does not scrub by accident — so it stays, with
 * nothing to arbitrate against.
 *
 * A plain class so the state machine is unit-testable on the JVM; [playerDragGestures] is the
 * only Compose-facing piece.
 */
internal class PlayerGestureHandler(private val host: Host) {

    /** The player screen's side: current values read once when a drag locks, changes pushed out. */
    interface Host {
        /** Whether a seek gesture may lock right now: a seekable item with a known duration. */
        fun canSeek(): Boolean

        /** The playback position a locking seek gesture scrubs from. */
        fun seekStartMs(): Long

        /** The end of the seekable range — the duration. */
        fun seekDurationMs(): Long

        /** The finger is mid-scrub: where it would land, and how far that is from the start. */
        fun onSeekPreview(targetMs: Long, deltaMs: Long)

        /** The finger lifted off a scrub: seek. */
        fun onSeekCommit(targetMs: Long)

        /** A locked drag's pointer went up or was cancelled. */
        fun onGestureEnd()
    }

    private enum class Control { SEEK, IGNORED }

    private var size = IntSize.Zero
    private var start = Offset.Zero
    private var dragged = Offset.Zero
    private var control: Control? = null
    private var seekStartMs = 0L
    private var seekDurationMs = 0L

    fun onDragStart(position: Offset, surfaceSize: IntSize) {
        size = surfaceSize
        start = position
        dragged = Offset.Zero
        control = null
    }

    fun onDrag(delta: Offset) {
        if (size.height <= 0 || size.width <= 0) return
        dragged += delta
        if (control == null) {
            control = decideControl()?.also(::lock)
        }
        when (control) {
            Control.SEEK -> {
                val target = seekTarget()
                host.onSeekPreview(target, target - seekStartMs)
            }
            Control.IGNORED, null -> Unit
        }
    }

    fun onDragEnd() {
        when (control) {
            Control.SEEK -> {
                host.onSeekCommit(seekTarget())
                host.onGestureEnd()
            }
            Control.IGNORED, null -> Unit
        }
        control = null
    }

    /** Reads the locked control's starting point, so the first movement adjusts from reality. */
    private fun lock(control: Control) {
        when (control) {
            Control.SEEK -> {
                seekStartMs = host.seekStartMs()
                seekDurationMs = host.seekDurationMs()
            }
            Control.IGNORED -> Unit
        }
    }

    /** Cumulative horizontal travel mapped linearly: a full surface width is [SEEK_FULL_WIDTH_MS]. */
    private fun seekTarget(): Long {
        val deltaMs = (dragged.x / size.width * SEEK_FULL_WIDTH_MS).toLong()
        return (seekStartMs + deltaMs).coerceIn(0L, seekDurationMs)
    }

    /**
     * Null while the drag hasn't clearly picked an axis yet — it keeps accumulating. Once locked
     * the control never changes for the rest of the gesture, which is what stops a drag that
     * began vertically from turning into a scrub when the finger arcs.
     */
    private fun decideControl(): Control? {
        val dx = abs(dragged.x)
        val dy = abs(dragged.y)
        return when {
            dy > dx * AXIS_LOCK_RATIO -> Control.IGNORED
            dx > dy * AXIS_LOCK_RATIO -> when {
                start.x < size.width * EDGE_EXCLUSION_RATIO -> Control.IGNORED
                start.x > size.width * (1f - EDGE_EXCLUSION_RATIO) -> Control.IGNORED
                !host.canSeek() -> Control.IGNORED
                else -> Control.SEEK
            }
            else -> null
        }
    }
}

/** Wires [handler] to a surface; meant for the player screen's root box. */
internal fun Modifier.playerDragGestures(handler: PlayerGestureHandler): Modifier =
    pointerInput(handler) {
        detectDragGestures(
            onDragStart = { offset -> handler.onDragStart(offset, size) },
            onDrag = { _, dragAmount -> handler.onDrag(dragAmount) },
            onDragEnd = handler::onDragEnd,
            onDragCancel = handler::onDragEnd,
        )
    }

/**
 * Calls [onRelease] every time a gesture ends — whoever consumed the events in between.
 *
 * The whole point is the pass it listens on. [PointerEventPass.Initial] is dispatched before the
 * Main pass, where the tap detector consumes the rest of a long press's event stream; a Main-pass
 * `waitForUpOrCancellation` reads that consumption as a cancellation and fires early, which is
 * why press-and-hold cannot find its own release any other way. Meant to run alongside
 * `detectTapGestures` in one `pointerInput`, whose `onLongPress` has no release half.
 */
internal suspend fun PointerInputScope.awaitGestureReleases(onRelease: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var event: PointerEvent
        do {
            event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        onRelease()
    }
}

/** What the feedback pill shows while a gesture is adjusting something. */
internal sealed interface GestureIndicator {
    /** A scrub in progress: where the finger would land, and how far that is from the start. */
    data class Seek(val targetMs: Long, val deltaMs: Long) : GestureIndicator

    /** Press-and-hold's temporary speed override, for as long as the finger stays down. */
    data class Speed(val speed: Float) : GestureIndicator
}

/** The transient feedback pill for a drag in progress. */
@Composable
internal fun GestureIndicatorPill(indicator: GestureIndicator, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (indicator) {
            is GestureIndicator.Seek -> SeekIndicator(indicator)
            is GestureIndicator.Speed -> SpeedIndicator(indicator)
        }
    }
}

/** Hold-to-speed: the speedometer and the multiplier currently forced. */
@Composable
private fun SpeedIndicator(indicator: GestureIndicator.Speed) {
    Icon(Icons.Filled.Speed, contentDescription = null, tint = Color.White)
    Text(
        formatSpeed(indicator.speed),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Scrub: direction icon, the landing position, and the signed distance being jumped. */
@Composable
private fun SeekIndicator(indicator: GestureIndicator.Seek) {
    Icon(
        if (indicator.deltaMs >= 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
        contentDescription = null,
        tint = Color.White,
    )
    Text(
        formatPosition(indicator.targetMs),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
    val sign = if (indicator.deltaMs < 0) "−" else "+"
    Text(
        sign + formatPosition(abs(indicator.deltaMs)),
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelLarge,
    )
}

/** A drag must travel twice as far along one axis as the other before it locks. */
private const val AXIS_LOCK_RATIO = 2f

/** Drags starting within this fraction of an edge belong to the system's edge gestures. */
private const val EDGE_EXCLUSION_RATIO = 0.05f

/** A horizontal drag across the whole surface width travels this far through the video. */
private const val SEEK_FULL_WIDTH_MS = 90_000L
