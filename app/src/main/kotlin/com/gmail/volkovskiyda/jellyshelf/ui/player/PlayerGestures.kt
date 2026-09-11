package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import com.gmail.volkovskiyda.jellyshelf.domain.model.VideoScaleMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * Press-and-hold's temporary speed, and the horizontal swipe that retunes it without lifting.
 *
 * A hold starts at [PlaybackSpeed.HOLD_DEFAULT] and every [stepPx] of travel from the point the
 * press landed moves one rung along [PlaybackSpeed.holdOptions], right for faster, clamped at both
 * ends. Travel is measured from that anchor rather than accumulated per event, so sliding back
 * returns through exactly the same speeds — an incremental sum would drift by a rung and never
 * come home.
 *
 * A plain class so the ladder arithmetic is unit-testable on the JVM, matching
 * [PlayerGestureHandler]; the screen owns what a new speed *does* (override, pill, haptics).
 */
internal class HoldSpeedTracker(private val host: Host) {

    /** The player screen's side: a hold's speed as it changes, and the end of the hold. */
    interface Host {
        /** The hold's speed, on the press itself and again on every rung the swipe crosses. */
        fun onHoldSpeed(speed: Float)

        /** The held finger lifted — only ever after an [onHoldSpeed]. */
        fun onHoldEnd()
    }

    /** Whether a press-and-hold is in flight, which is what keeps a swipe off the seek path. */
    var isHolding = false
        private set

    private var startX = 0f
    private var stepPx = 1f
    private var anchorIndex = 0
    private var index = 0

    /** The long press fired: force the default and anchor the swipe to where the finger is. */
    fun start(position: Offset, stepPx: Float) {
        isHolding = true
        startX = position.x
        this.stepPx = if (stepPx > 0f) stepPx else 1f
        anchorIndex = PlaybackSpeed.holdOptions.indexOf(PlaybackSpeed.HOLD_DEFAULT)
        index = anchorIndex
        host.onHoldSpeed(PlaybackSpeed.holdOptions[index])
    }

    /** Inert unless a hold is in flight: this sees every gesture's movement, not only a hold's. */
    fun move(position: Offset) {
        if (!isHolding) return
        val steps = ((position.x - startX) / stepPx).roundToInt()
        val moved = (anchorIndex + steps).coerceIn(PlaybackSpeed.holdOptions.indices)
        if (moved == index) return
        index = moved
        host.onHoldSpeed(PlaybackSpeed.holdOptions[moved])
    }

    /** The finger lifted, or the gesture was cancelled. Safe on a gesture that never held. */
    fun end() {
        if (!isHolding) return
        isHolding = false
        host.onHoldEnd()
    }
}

/**
 * The player surface's finger gestures that are not drags: tap, double-tap, and press-and-hold
 * with the swipe that retunes its speed.
 *
 * All of it belongs in one pointer node because the halves are not separable. `detectTapGestures`
 * consumes the rest of a long press's event stream, so a gesture detector downstream of it — the
 * scrub's `detectDragGestures`, say — rejects the swipe as already handled; only a reader sitting
 * beside it in the same node sees the movement at all (see [awaitHoldPointer]). Splitting them
 * across two `pointerInput` nodes would also let one restart mid-gesture and strand a hold at
 * speed with nothing left to undo it.
 *
 * [key] is what the node restarts on; pass everything the callbacks close over. Kept here rather
 * than inline on the screen so an instrumented test can drive the real wiring.
 */
internal fun Modifier.playerTapGestures(
    key: Any?,
    holdSpeed: HoldSpeedTracker,
    onTap: () -> Unit,
    onDoubleTap: (Offset, IntSize) -> Unit,
    onHoldRelease: () -> Unit,
): Modifier = pointerInput(key) {
    coroutineScope {
        launch {
            detectTapGestures(
                onDoubleTap = { offset -> onDoubleTap(offset, size) },
                onLongPress = { offset -> holdSpeed.start(offset, HOLD_SPEED_STEP.toPx()) },
                onTap = { onTap() },
            )
        }
        // onLongPress has neither a movement nor a release half; this supplies both.
        launch {
            awaitHoldPointer(onMove = holdSpeed::move) {
                holdSpeed.end()
                onHoldRelease()
            }
        }
    }
}

/**
 * Reports the pointer's position for the whole of every gesture, then calls [onRelease] when it
 * ends — whoever consumed the events in between.
 *
 * The pass it listens on is what makes it work. [PointerEventPass.Initial] is dispatched before
 * the Main pass, where the tap detector consumes the rest of a long press's event stream; a
 * Main-pass `waitForUpOrCancellation` reads that consumption as a cancellation and fires early,
 * which is why press-and-hold cannot find its own release any other way. Reading the raw event
 * loop rather than that helper is the other half: a consumed change is still *delivered*, so the
 * positions a swipe-while-held is made of survive the tap detector either way — but only here,
 * beside it, since anything downstream treats the consumption as a gesture already claimed.
 *
 * Nothing is consumed here — the tap detector on the Main pass still has to see its own taps.
 * Meant to run alongside `detectTapGestures` in one `pointerInput`, whose `onLongPress` has
 * neither a movement nor a release half.
 */
internal suspend fun PointerInputScope.awaitHoldPointer(
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var event: PointerEvent
        do {
            event = awaitPointerEvent(PointerEventPass.Initial)
            // By id rather than the first change: a second finger landing must not hand the hold
            // a position that jumps across the surface.
            event.changes.firstOrNull { it.id == down.id }?.let { onMove(it.position) }
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

    /**
     * The scale button's confirmation. Not a gesture, but the same pill: the mode it names is the
     * only feedback for a tap whose effect on a frame the video already fills is invisible.
     */
    data class ScaleMode(val mode: VideoScaleMode) : GestureIndicator
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
            is GestureIndicator.ScaleMode -> ScaleModeIndicator(indicator)
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

/** A scale-mode tap: the button's own icon and the name of the mode now in force. */
@Composable
private fun ScaleModeIndicator(indicator: GestureIndicator.ScaleMode) {
    Icon(Icons.Filled.AspectRatio, contentDescription = null, tint = Color.White)
    Text(
        stringResource(indicator.mode.labelRes()),
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

/**
 * How far a held finger travels to move one rung along [PlaybackSpeed.holdOptions].
 *
 * Wide enough that a hold meant to sit still does not drift off 2×, narrow enough that both ends
 * of the ladder are in reach: six rungs down to 0.5× is 192 dp, four up to 5× is 128 dp.
 *
 * Internal rather than private so the gesture test measures its travel in real rungs.
 */
internal val HOLD_SPEED_STEP = 32.dp

/** A drag must travel twice as far along one axis as the other before it locks. */
private const val AXIS_LOCK_RATIO = 2f

/** Drags starting within this fraction of an edge belong to the system's edge gestures. */
private const val EDGE_EXCLUSION_RATIO = 0.05f

/** A horizontal drag across the whole surface width travels this far through the video. */
private const val SEEK_FULL_WIDTH_MS = 90_000L
