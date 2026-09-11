package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player surface's press-and-hold, driven through the real [playerTapGestures] modifier with a
 * real [HoldSpeedTracker] and Compose's own tap detector.
 *
 * [HoldSpeedTrackerTest] already pins the ladder arithmetic on the JVM; what only a device can
 * answer is whether the swipe reaches the tracker at all. `detectTapGestures` consumes the rest of
 * a long press's event stream, so a movement handler anywhere downstream of it treats the swipe as
 * a gesture already claimed and sees nothing. Reading it beside the detector in the same node is
 * what makes it work, and these are the tests that fail if that arrangement stops holding —
 * checked by removing the movement callback, which turns both slide cases red.
 *
 * The surface here carries no player: the tracker reports speeds, and what the screen does with
 * them (the override, the pill, the haptic tick) is wiring these assertions deliberately skip.
 */
@RunWith(AndroidJUnit4::class)
class PlayerTapGesturesTest {

    /**
     * [UnconfinedTestDispatcher] for the reason PlayerControlsTest gives, sharpened here: a
     * `pointerInput` block is a coroutine launched by the composition, and the v2 default
     * `StandardTestDispatcher` queues it without ever dispatching it. The gesture detectors then
     * never start, and every assertion in this class reads an empty list — a silent pass for the
     * tests asserting inaction, which is exactly the shape that hides a broken gesture.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(
        effectContext = UnconfinedTestDispatcher(),
    )

    private val speeds = mutableListOf<Float>()
    private var taps = 0
    private var doubleTaps = 0
    private var releases = 0
    private var holdEnds = 0
    private val seekPreviews = mutableListOf<Long>()
    private var stepPx = 0f
    private var longPressMs = 0L
    private var doubleTapMs = 0L

    private lateinit var tracker: HoldSpeedTracker

    /** Mirrors the screen's own host: the scrub refuses to lock while a hold owns the finger. */
    private fun dragHandler(suppressWhileHolding: Boolean) =
        PlayerGestureHandler(object : PlayerGestureHandler.Host {
            override fun canSeek() = !(suppressWhileHolding && tracker.isHolding)
            override fun seekStartMs() = SEEK_START_MS
            override fun seekDurationMs() = SEEK_DURATION_MS
            override fun onSeekPreview(targetMs: Long, deltaMs: Long) {
                seekPreviews += targetMs
            }
            override fun onSeekCommit(targetMs: Long) = Unit
            override fun onGestureEnd() = Unit
        })

    private fun setSurface(withDragNode: Boolean = false, suppressWhileHolding: Boolean = true) {
        composeRule.setContent {
            stepPx = with(LocalDensity.current) { HOLD_SPEED_STEP.toPx() }
            longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
            doubleTapMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
            tracker = HoldSpeedTracker(object : HoldSpeedTracker.Host {
                override fun onHoldSpeed(speed: Float) {
                    speeds += speed
                }

                override fun onHoldEnd() {
                    holdEnds++
                }
            })
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(SURFACE)
                    .playerTapGestures(
                        key = Unit,
                        holdSpeed = tracker,
                        onTap = { taps++ },
                        onDoubleTap = { _, _ -> doubleTaps++ },
                        onHoldRelease = { releases++ },
                    )
                    // The screen stacks the scrub node under the tap node in this order; the
                    // suppression under test only means anything with both of them present.
                    .then(
                        if (withDragNode) {
                            Modifier.playerDragGestures(
                                remember { dragHandler(suppressWhileHolding) },
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }

    private fun surface() = composeRule.onNodeWithTag(SURFACE)

    /**
     * Presses, waits out the long press, walks the finger through [stops], and lifts — one
     * injected gesture, because the finger must never come up between the hold and the swipe.
     *
     * Time is moved with the injection scope's own `advanceEventTime` rather than `mainClock`:
     * the long press is detected off the timestamps on the pointer events, so advancing the frame
     * clock alone leaves the detector waiting for an event that never comes.
     */
    private fun holdAndSlide(start: Offset, vararg stops: Float) {
        surface().performTouchInput {
            down(start)
            advanceEventTime(longPressMs + LONG_PRESS_MARGIN_MS)
            stops.forEach { dx ->
                moveTo(Offset(start.x + dx, start.y))
                advanceEventTime(FRAME_MS)
            }
            up()
        }
        composeRule.waitForIdle()
    }

    private fun centre(): Offset =
        surface().fetchSemanticsNode().size.let { Offset(it.width / 2f, it.height / 2f) }

    @Test
    fun holdingStill_forcesTheDefaultSpeed() {
        setSurface()

        holdAndSlide(centre())

        assertEquals(listOf(PlaybackSpeed.HOLD_DEFAULT), speeds)
        assertEquals(1, releases)
        assertEquals(1, holdEnds)
        assertFalse(tracker.isHolding)
    }

    /**
     * The heart of it: the finger never lifts between the hold and the swipe, which is exactly the
     * stretch of the event stream the tap detector has consumed.
     */
    @Test
    fun slidingRightWhileHeld_walksUpTheLadder() {
        setSurface()
        val start = centre()

        holdAndSlide(start, stepPx, 2 * stepPx)

        assertEquals(listOf(2f, 2.5f, 3f), speeds)
        assertEquals(1, releases)
    }

    @Test
    fun slidingLeftWhileHeld_walksDownTheLadder() {
        setSurface()
        val start = centre()

        holdAndSlide(start, -stepPx, -2 * stepPx)

        assertEquals(listOf(2f, 1.75f, 1.5f), speeds)
    }

    /** A hold is not a tap: neither callback may fire when the finger lifts after one. */
    @Test
    fun aHoldIsNotATap() {
        setSurface()

        holdAndSlide(centre())

        assertEquals(0, taps)
        assertEquals(0, doubleTaps)
    }

    /**
     * The other half of that: the tap detector still works with the Initial-pass reader beside it.
     *
     * A double-tap rather than a single one because it needs no timeout to resolve — the second
     * press is the signal — while `onTap` must first wait out the double-tap window. What is being
     * protected here is that the reader does not swallow the surface's taps, and either gesture
     * proves that equally.
     */
    @Test
    fun aDoubleTapStillCountsAndHoldsNothing() {
        setSurface()
        val at = centre()

        surface().performTouchInput {
            down(at)
            up()
            advanceEventTime(SECOND_TAP_GAP_MS)
            down(at)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, doubleTaps)
        assertEquals(0, taps)
        assertTrue(speeds.isEmpty())
        assertFalse(tracker.isHolding)
        // Every gesture ends, hold or not — the release half is not told which it was.
        assertEquals(2, releases)
        assertEquals(0, holdEnds)
    }

    /** A swipe with no hold behind it belongs to the scrub gesture, which is a different node. */
    @Test
    fun swipingWithoutHolding_changesNoSpeed() {
        setSurface()
        val start = centre()

        surface().performTouchInput {
            down(start)
            moveTo(Offset(start.x + 3 * stepPx, start.y))
            advanceEventTime(FRAME_MS)
            up()
        }
        composeRule.waitForIdle()

        assertTrue(speeds.isEmpty())
        assertFalse(tracker.isHolding)
    }

    /**
     * Holding and then sliding must not also scrub. The scrub lives in its own pointer node under
     * this one, and the screen keeps it out of the way by refusing
     * [PlayerGestureHandler.Host.canSeek] for as long as a hold owns the finger — this is the test
     * that the refusal is wired up and is enough.
     */
    @Test
    fun slidingWhileHeld_neverScrubs() {
        setSurface(withDragNode = true)
        val start = centre()

        holdAndSlide(start, stepPx, 2 * stepPx)

        assertEquals(listOf(2f, 2.5f, 3f), speeds)
        assertTrue("a hold's swipe also scrubbed: $seekPreviews", seekPreviews.isEmpty())
    }

    /**
     * The other side of it: with the refusal taken out, the same gesture *does* reach the scrub.
     * Without this the test above would pass on a surface where the scrub could never have locked
     * anyway, and the guard it claims to check would be decoration.
     */
    @Test
    fun withoutTheRefusal_theSameGestureWouldScrub() {
        setSurface(withDragNode = true, suppressWhileHolding = false)
        val start = centre()

        holdAndSlide(start, stepPx, 2 * stepPx)

        assertEquals(listOf(2f, 2.5f, 3f), speeds)
        assertTrue("the scrub never locked, so the refusal guards nothing", seekPreviews.isNotEmpty())
    }

    private companion object {
        const val SURFACE = "player-surface"

        /** A seekable-looking video, so a scrub that does lock has somewhere to land. */
        const val SEEK_START_MS = 60_000L
        const val SEEK_DURATION_MS = 600_000L

        /** Comfortably past the platform's long-press timeout, so the press is never borderline. */
        const val LONG_PRESS_MARGIN_MS = 200L

        /** The same, for the window `onTap` waits out before it can rule out a second tap. */
        const val DOUBLE_TAP_MARGIN_MS = 100L

        /** A plausible gap between moves; any non-zero step keeps the events distinctly timed. */
        const val FRAME_MS = 16L

        /**
         * Long enough apart for Compose to count two taps as a double-tap: its detector ignores a
         * second press arriving within `doubleTapMinTimeMillis` (40 ms) of the first one lifting.
         */
        const val SECOND_TAP_GAP_MS = 60L
    }
}
