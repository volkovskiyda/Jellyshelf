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
 * whole range. Decisively horizontal drags are recognized and locked so they can't turn into a
 * volume change mid-gesture, but do nothing yet (swipe-to-seek is the follow-up). Drags starting
 * in the edge strips are left to the system's own edge gestures.
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

        fun onVolumeChange(fraction: Float)

        fun onBrightnessChange(fraction: Float)

        /** A volume/brightness drag's pointer went up or was cancelled. */
        fun onGestureEnd()
    }

    private enum class Control { VOLUME, BRIGHTNESS, IGNORED }

    private var size = IntSize.Zero
    private var start = Offset.Zero
    private var dragged = Offset.Zero
    private var control: Control? = null
    private var value = 0f

    fun onDragStart(position: Offset, surfaceSize: IntSize) {
        size = surfaceSize
        start = position
        dragged = Offset.Zero
        control = null
    }

    fun onDrag(delta: Offset) {
        if (size.height <= 0) return
        dragged += delta
        if (control == null) {
            control = decideControl()?.also { locked ->
                value = when (locked) {
                    Control.VOLUME -> host.volumeFraction()
                    Control.BRIGHTNESS -> host.brightnessFraction()
                    Control.IGNORED -> 0f
                }
            }
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
            Control.IGNORED, null -> Unit
        }
    }

    fun onDragEnd() {
        if (control == Control.VOLUME || control == Control.BRIGHTNESS) host.onGestureEnd()
        control = null
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
            dx > dy * AXIS_LOCK_RATIO -> Control.IGNORED
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
internal data class GestureIndicator(val control: IndicatorControl, val fraction: Float)

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

/** The transient feedback pill for a drag in progress: icon plus a localized percentage. */
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
}

/** A drag must travel twice as far along one axis as the other before it locks. */
private const val AXIS_LOCK_RATIO = 2f

/** Dragging this fraction of the surface height sweeps the whole volume/brightness range. */
private const val FULL_SWIPE_RANGE_RATIO = 0.66f

/** Drags starting within this fraction of an edge belong to the system's edge gestures. */
private const val EDGE_EXCLUSION_RATIO = 0.05f

/** Vertical drags starting right of this width fraction adjust volume; left of it, brightness. */
private const val VOLUME_ZONE_START = 0.5f

/** Mid-range stand-in when there is no window to read a brightness from. */
private const val DEFAULT_BRIGHTNESS_FRACTION = 0.5f

/** [Settings.System.SCREEN_BRIGHTNESS] is 0..255 on stock Android. */
private const val MAX_SYSTEM_BRIGHTNESS = 255f

/** Fallback when the system brightness setting is unreadable: mid-range. */
private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
