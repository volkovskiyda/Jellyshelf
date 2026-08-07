package com.gmail.volkovskiyda.jellyshelf.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val CONTENT_TAG = "revealTestContent"
private const val DARK = "dark"
private const val LIGHT = "light"

/** Longer than the 400 ms reveal, so a finished animation is never mistaken for a missing one. */
private const val SETTLE_MS = 1_000L

/**
 * [ThemeReveal]'s contract, which is mostly about *when not to animate*: only a change with a freshly
 * armed origin gets the circular reveal, and while one plays the screen belongs to it.
 *
 * The compose clock runs manually here (`autoAdvance = false`) rather than automatically. It has to:
 * every test action waits for idle first, and an auto-advancing clock reaches idle by running the
 * whole 400 ms animation to its end — which would leave no moment at which the overlay could be
 * observed at all.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class ThemeRevealTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Deliberately without enableAccessibilityChecks(), unlike every other Compose-rule test here:
    // the checks run before each action that changes the UI, and this test halts the clock to
    // assert on specific frames of the reveal — they would perturb exactly what it measures. The
    // switch's own accessibility is covered by ThemeModeSwitchTest.

    private val controller = ThemeRevealController()

    /** Held outside the composition, the way the real theme flow is: the target, not what is drawn. */
    private var target by mutableStateOf(false)
    private var taps = 0

    private fun setContent() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ThemeReveal(controller = controller, darkTheme = target) { appliedDark ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Real pixels in both themes, so the captured frame is a meaningful one.
                        .background(if (appliedDark) Color.Black else Color.White)
                        .clickable { taps++ },
                    contentAlignment = Alignment.Center,
                ) {
                    // What the content was themed with, in a form the semantics tree can report.
                    Text(
                        text = if (appliedDark) DARK else LIGHT,
                        color = if (appliedDark) Color.White else Color.Black,
                        modifier = Modifier.testTag(CONTENT_TAG),
                    )
                }
            }
        }
        // One drawn frame, so there is something recorded in the layer to capture.
        drainFrames(4)
    }

    @Test
    fun a_flip_with_nothing_armed_switches_instantly() {
        setContent()
        assertApplied(LIGHT)

        flipTo(dark = true)

        // Watched frame by frame rather than judged after the fact: an animation that ran and
        // finished would look identical at the end.
        repeat(8) {
            assertFalse("An unarmed change animated", controller.isAnimating)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        assertEquals("The reveal overlay is still on screen", 0, overlayNodeCount())
        assertApplied(DARK)
    }

    @Test
    fun an_armed_flip_reveals_and_swallows_taps_until_it_ends() {
        setContent()
        armFromCenter()

        flipTo(dark = true)
        awaitReveal()

        assertEquals("The reveal overlay is missing", 1, overlayNodeCount())
        // The theme flips underneath the overlay, in the frame the overlay first covers everything.
        assertApplied(DARK)

        composeRule.onNode(hasClickAction()).performClick()
        assertTrue("The reveal ended before the tap could be tested", controller.isAnimating)
        assertEquals("A tap reached the UI through the reveal", 0, taps)

        settle()
        assertEquals("The reveal overlay is still on screen", 0, overlayNodeCount())
        assertApplied(DARK)
        // And the app is responsive the moment the overlay is gone.
        composeRule.onNode(hasClickAction()).performClick()
        assertEquals(1, taps)
    }

    /** Going back the other way is the [androidx.compose.ui.graphics.ClipOp.Intersect] direction. */
    @Test
    fun an_armed_flip_back_to_light_reveals_too() {
        setContent()
        armFromCenter()
        flipTo(dark = true)
        awaitReveal()
        settle()

        armFromCenter()
        flipTo(dark = false)
        awaitReveal()

        assertEquals("The reveal overlay is missing", 1, overlayNodeCount())
        settle()
        assertApplied(LIGHT)
    }

    @Test
    fun an_origin_is_spent_by_the_change_it_animates() {
        setContent()
        armFromCenter()
        flipTo(dark = true)
        awaitReveal()
        settle()

        // No second arming: the next change has to be instant.
        flipTo(dark = false)
        repeat(8) {
            assertFalse("A spent origin animated a second change", controller.isAnimating)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        assertApplied(LIGHT)
    }

    @Test
    fun an_origin_older_than_the_arming_window_is_ignored() {
        setContent()
        armFromCenter()

        // Real sleep: the arming window is measured with SystemClock.uptimeMillis(), which the
        // compose test clock does not move. One second, once.
        Thread.sleep(1_100)

        flipTo(dark = true)
        repeat(8) {
            assertFalse("A stale origin still animated", controller.isAnimating)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        assertEquals("The reveal overlay is still on screen", 0, overlayNodeCount())
        assertApplied(DARK)
    }

    private fun armFromCenter() {
        controller.armReveal(composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center)
    }

    private fun flipTo(dark: Boolean) {
        // Not runOnIdle: waiting for idle here would let the reveal start and finish unobserved.
        composeRule.runOnUiThread { target = dark }
    }

    /**
     * Runs frames until the snapshot overlay is on screen. Waiting on the node rather than on
     * [ThemeRevealController.isAnimating] matters: the controller flips a frame before the
     * recomposition that actually adds the overlay, and with a manual clock that frame has to be
     * asked for.
     */
    private fun awaitReveal() {
        val deadline = System.currentTimeMillis() + SETTLE_MS * 5
        while (overlayNodeCount() == 0) {
            if (System.currentTimeMillis() > deadline) fail("The reveal never started")
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun overlayNodeCount(): Int = composeRule
        .onAllNodesWithTag(THEME_REVEAL_OVERLAY_TAG, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .size

    /** Lets the running animation finish and the overlay be dropped. */
    private fun settle() {
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        composeRule.waitForIdle()
    }

    private fun drainFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun assertApplied(theme: String) {
        composeRule.onNodeWithTag(CONTENT_TAG, useUnmergedTree = true).assertTextEquals(theme)
    }
}
