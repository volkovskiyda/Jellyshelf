package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Axis-locked drag gestures over the player surface — the pattern the Jellyfin and YouTube apps
 * use. A drag that is decisively vertical (2:1 over its horizontal travel, the Jellyfin app's
 * rule) adjusts the music volume when it starts on the right half of the surface and the screen
 * brightness on the left; sweeping [FULL_SWIPE_RANGE_RATIO] of the surface height covers the
 * whole range. A decisively horizontal drag scrubs: a full surface width travels
 * [SEEK_FULL_WIDTH_MS] through the video, the target is previewed while the finger moves, and
 * the seek itself fires on release — also the Jellyfin app's behavior. Drags starting in the
 * edge strips are left to the system's own edge gestures.
 *
 * A plain class so the state machine is unit-testable on the JVM; [playerDragGestures] is the
 * only Compose-facing piece.
 */
internal class PlayerGestureHandler(private val host: Host) {

    /** The player screen's side: current values read once when a drag locks, changes pushed out. */
    interface Host {
        /** Music-stream volume as a 0..1 fraction. */
        fun volumeFraction(): Float

        /** Window brightness as a 0..1 fraction, seeded from the system value when unset. */
        fun brightnessFraction(): Float

        /** Whether a seek gesture may lock right now: a seekable item with a known duration. */
        fun canSeek(): Boolean

        /** The playback position a locking seek gesture scrubs from. */
        fun seekStartMs(): Long

        /** The end of the seekable range — the duration. */
        fun seekDurationMs(): Long

        fun onVolumeChange(fraction: Float)

        fun onBrightnessChange(fraction: Float)

        /** The finger is mid-scrub: where it would land, and how far that is from the start. */
        fun onSeekPreview(targetMs: Long, deltaMs: Long)

        /** The finger lifted off a scrub: seek. */
        fun onSeekCommit(targetMs: Long)

        /** A locked drag's pointer went up or was cancelled. */
        fun onGestureEnd()
    }

    private enum class Control { VOLUME, BRIGHTNESS, SEEK, IGNORED }

    private var size = IntSize.Zero
    private var start = Offset.Zero
    private var dragged = Offset.Zero
    private var control: Control? = null
    private var value = 0f
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
        // Upward drag increases; a FULL_SWIPE_RANGE_RATIO-of-the-height drag sweeps 0..1.
        val fraction = -delta.y / (size.height * FULL_SWIPE_RANGE_RATIO)
        when (control) {
            Control.VOLUME -> {
                value = (value + fraction).coerceIn(0f, 1f)
                host.onVolumeChange(value)
            }
            Control.BRIGHTNESS -> {
                value = (value + fraction).coerceIn(0f, 1f)
                host.onBrightnessChange(value)
            }
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
            Control.VOLUME, Control.BRIGHTNESS -> host.onGestureEnd()
            Control.IGNORED, null -> Unit
        }
        control = null
    }

    /** Reads the locked control's starting point, so the first movement adjusts from reality. */
    private fun lock(control: Control) {
        when (control) {
            Control.VOLUME -> value = host.volumeFraction()
            Control.BRIGHTNESS -> value = host.brightnessFraction()
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
     * began horizontally from turning into a volume jump when the finger arcs.
     */
    private fun decideControl(): Control? {
        val dx = abs(dragged.x)
        val dy = abs(dragged.y)
        return when {
            dy > dx * AXIS_LOCK_RATIO -> when {
                start.y < size.height * EDGE_EXCLUSION_RATIO -> Control.IGNORED
                start.y > size.height * (1f - EDGE_EXCLUSION_RATIO) -> Control.IGNORED
                start.x > size.width * VOLUME_ZONE_START -> Control.VOLUME
                else -> Control.BRIGHTNESS
            }
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

/** What the feedback pill shows while a drag is adjusting something. */
internal sealed interface GestureIndicator {
    /** A volume or brightness level, as a fraction of its range. */
    data class Level(val control: IndicatorControl, val fraction: Float) : GestureIndicator

    /** A scrub in progress: where the finger would land, and how far that is from the start. */
    data class Seek(val targetMs: Long, val deltaMs: Long) : GestureIndicator
}

internal enum class IndicatorControl { VOLUME, BRIGHTNESS }

/** Music-stream volume as a 0..1 fraction of the device's step range. */
internal fun AudioManager.musicVolumeFraction(): Float {
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0f
    return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

/** Sets the music stream to the nearest step of [fraction], without the system volume UI. */
internal fun AudioManager.setMusicVolumeFraction(fraction: Float) {
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt().coerceIn(0, max), 0)
}

/**
 * The window's current brightness override, else the user's system brightness, as 0..1 — the
 * seed a brightness drag starts from, so the first movement adjusts what the eye already sees.
 */
internal fun currentBrightnessFraction(activity: Activity?): Float {
    val window = activity?.window ?: return DEFAULT_BRIGHTNESS_FRACTION
    val override = window.attributes.screenBrightness
    if (override >= 0f) return override.coerceIn(0f, 1f)
    val system = Settings.System.getInt(
        activity.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        DEFAULT_SYSTEM_BRIGHTNESS,
    )
    return (system / MAX_SYSTEM_BRIGHTNESS).coerceIn(0f, 1f)
}

/** Applies [fraction] as this window's brightness override. */
internal fun applyBrightnessFraction(activity: Activity?, fraction: Float) {
    val window = activity?.window ?: return
    window.attributes = window.attributes.apply { screenBrightness = fraction }
}

/**
 * Clears the override. The window outlives the player screen in this single-activity app, so
 * leaving the player must restore the user's own brightness — an override would silently stick
 * to every other screen.
 */
internal fun clearBrightnessOverride(activity: Activity?) {
    val window = activity?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
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
            is GestureIndicator.Level -> LevelIndicator(indicator)
            is GestureIndicator.Seek -> SeekIndicator(indicator)
        }
    }
}

/** Volume/brightness: icon plus a localized percentage. */
@Composable
private fun LevelIndicator(indicator: GestureIndicator.Level) {
    Icon(
        when (indicator.control) {
            IndicatorControl.VOLUME -> if (indicator.fraction <= 0f) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            }
            IndicatorControl.BRIGHTNESS -> Icons.Filled.BrightnessHigh
        },
        contentDescription = null,
        tint = Color.White,
    )
    val percentFormat = remember { NumberFormat.getPercentInstance() }
    Text(
        percentFormat.format(indicator.fraction.toDouble()),
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

/** Dragging this fraction of the surface height sweeps the whole volume/brightness range. */
private const val FULL_SWIPE_RANGE_RATIO = 0.66f

/** Drags starting within this fraction of an edge belong to the system's edge gestures. */
private const val EDGE_EXCLUSION_RATIO = 0.05f

/** Vertical drags starting right of this width fraction adjust volume; left of it, brightness. */
private const val VOLUME_ZONE_START = 0.5f

/** A horizontal drag across the whole surface width travels this far through the video. */
private const val SEEK_FULL_WIDTH_MS = 90_000L

/** Mid-range stand-in when there is no window to read a brightness from. */
private const val DEFAULT_BRIGHTNESS_FRACTION = 0.5f

/** [Settings.System.SCREEN_BRIGHTNESS] is 0..255 on stock Android. */
private const val MAX_SYSTEM_BRIGHTNESS = 255f

/** Fallback when the system brightness setting is unreadable: mid-range. */
private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
