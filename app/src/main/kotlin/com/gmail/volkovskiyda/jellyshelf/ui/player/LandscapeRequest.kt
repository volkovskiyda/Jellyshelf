package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs

/** The two quarter turns, either way round, that a device held sideways reads as. */
private val LANDSCAPE_DEGREES = listOf(90, 270)

/** How far from one of [LANDSCAPE_DEGREES] still counts as holding the device sideways. */
private const val LANDSCAPE_TILT_SLACK = 35

/**
 * How long the device must *stay* sideways before the request is released.
 *
 * Time rather than a count of readings: [OrientationEventListener] reports only when the angle
 * changes, so a device held steady in landscape delivers exactly one event — a "two consecutive
 * readings" rule would wait for a second one that never comes and leave the app pinned sideways.
 * Any out-of-band reading inside the window cancels it, which is what a wobble through 90° is.
 */
private const val LANDSCAPE_CONFIRM_MS = 400L

/**
 * The player's rotate button, which asks for landscape **without locking the device there**.
 *
 * The request is handed straight back to the sensor as soon as the phone is physically landscape,
 * so one more turn returns to portrait exactly as it would have without the button. That release is
 * the whole point of the class: `requestedOrientation` alone would pin the app sideways until
 * something cleared it.
 *
 * Two cases hold the request instead of releasing it, because releasing would snap the app back to
 * portrait the same frame and make the button useless: the system auto-rotate setting being off, and
 * a device whose sensor cannot report orientation. Both are then released when the player screen
 * goes away (or the activity stops), which is why [release] is wired to the caller's lifecycle.
 *
 * On large screens Android 16+ may ignore an orientation request entirely; the player gates the
 * button on the window's smallest width, so the request is not made where it would do nothing.
 */
class LandscapeRequest(private val activity: ComponentActivity) : DefaultLifecycleObserver {
    private var listener: OrientationEventListener? = null

    /** Sensor callbacks and this both run on the main looper, so the flag needs no synchronisation. */
    private val handler = Handler(Looper.getMainLooper())
    private var confirming = false
    private val confirmLandscape = Runnable { if (confirming) release() }

    /** Asks for landscape, and arms the sensor watch that gives orientation back. */
    fun request() {
        // A PiP window has no orientation of its own to ask about, and the request would only
        // survive to fight the transition back out.
        if (activity.isInPictureInPictureMode) return
        // USER_LANDSCAPE rather than SENSOR_LANDSCAPE: with the system rotation lock on, this
        // settles on one side instead of flipping between the two as the phone is handled.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        if (!autoRotateEnabled() || listener != null) return
        listener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(degrees: Int) {
                if (!isLandscapeTilt(degrees)) {
                    confirming = false
                    handler.removeCallbacks(confirmLandscape)
                    return
                }
                if (confirming) return
                confirming = true
                handler.postDelayed(confirmLandscape, LANDSCAPE_CONFIRM_MS)
            }
        }.takeIf { it.canDetectOrientation() }?.also { it.enable() }
    }

    /** Drops any outstanding request and stops watching the sensor. Safe to call unconditionally. */
    fun release() {
        confirming = false
        handler.removeCallbacks(confirmLandscape)
        listener?.disable()
        listener = null
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** No sensor while backgrounded — and the listener holds the activity, so this is not optional. */
    override fun onStop(owner: LifecycleOwner) = release()

    override fun onDestroy(owner: LifecycleOwner) = release()

    /**
     * Read at tap time rather than observed: flipping the system rotation lock mid-video is not
     * worth a ContentObserver, and the next tap picks up the change anyway.
     */
    private fun autoRotateEnabled(): Boolean = Settings.System.getInt(
        activity.contentResolver,
        Settings.System.ACCELEROMETER_ROTATION,
        0,
    ) == 1
}

/**
 * Whether [degrees] reads as the device being held sideways, with slack in both directions.
 *
 * `ORIENTATION_UNKNOWN` (-1) is what a device flat on a table reports, and must not count as
 * landscape — releasing there would drop the app back to portrait while the video is playing.
 */
internal fun isLandscapeTilt(degrees: Int): Boolean =
    degrees != OrientationEventListener.ORIENTATION_UNKNOWN &&
        LANDSCAPE_DEGREES.any { abs(degrees - it) <= LANDSCAPE_TILT_SLACK }
