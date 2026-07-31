package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import com.gmail.volkovskiyda.jellyshelf.ui.theme.LocalThemeRevealController
import com.gmail.volkovskiyda.jellyshelf.ui.theme.ThemeRevealController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The theme switch's tap behavior through real UI wiring. [ThemeStateTest] pins the sequence
 * itself; what this adds is that a tap on the control actually advances it, that the state a
 * screen reader announces follows along, and that the whole control is one target rather than
 * three (a tap on the left edge must not jump straight to light).
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class ThemeModeSwitchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Fails this class's tests on unlabelled clickables, undersized touch targets and unreadable
     * contrast — checked before every action that changes the UI, so the whole rendered tree is
     * covered, not only the nodes an assertion happens to name.
     */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun setContent(initial: ThemeState = ThemeState()) {
        // Held outside the composition, the way the real ViewModel holds it: created inside, it
        // would reset on every recomposition and no click would ever stick.
        var state by mutableStateOf(initial)
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                ThemeModeSwitch(mode = state.mode, onClick = { state = state.next() })
            }
        }
    }

    /** The same switch, but composed under a reveal controller the way [MainActivity] provides one. */
    private fun setContentWithReveal(controller: ThemeRevealController) {
        var state by mutableStateOf(ThemeState())
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                CompositionLocalProvider(LocalThemeRevealController provides controller) {
                    ThemeModeSwitch(mode = state.mode, onClick = { state = state.next() })
                }
            }
        }
    }

    /** The switch is the only clickable node under test, so this finds it whatever state it is in. */
    private fun switch() = composeRule.onNode(hasClickAction())

    private fun assertMode(mode: ThemeMode) {
        val label = composeRule.activity.getString(mode.labelRes)
        switch().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
    }

    @Test
    fun taps_ping_pong_through_auto_rather_than_wrapping_around() {
        setContent()

        assertMode(ThemeMode.AUTO)
        switch().performClick()
        assertMode(ThemeMode.DARK)
        switch().performClick()
        assertMode(ThemeMode.AUTO)
        switch().performClick()
        assertMode(ThemeMode.LIGHT)
        // The end bounces back to auto instead of wrapping round to dark.
        switch().performClick()
        assertMode(ThemeMode.AUTO)
    }

    /**
     * One click target, not three: a tap anywhere advances by a single step, so tapping while the
     * dark icon sits under the finger must not select dark directly.
     */
    @Test
    fun a_tap_advances_one_step_wherever_it_lands() {
        setContent(ThemeState(ThemeMode.LIGHT, towardDark = true))

        switch().performClick()

        assertMode(ThemeMode.AUTO)
    }

    /**
     * A tap arms the circular reveal from the control's own centre, which is what lets the new
     * theme grow out of the switch rather than replace the screen. The arming is spent by the first
     * reader, so a tap that turns out to change nothing visible cannot animate a later, unrelated
     * change.
     */
    @Test
    fun a_tap_arms_the_reveal_from_the_control_centre() {
        val controller = ThemeRevealController()
        setContentWithReveal(controller)

        switch().performClick()

        val expected = switch().fetchSemanticsNode().boundsInWindow.center
        val origin = controller.consumeOrigin()
        assertNotNull("The tap armed no origin", origin)
        assertEquals(expected.x, origin!!.x, 1f)
        assertEquals(expected.y, origin.y, 1f)
        assertNull("The origin survived being consumed", controller.consumeOrigin())
    }

    /** Nothing provides a controller in previews or screenshot tests; the switch still has to work. */
    @Test
    fun a_tap_without_a_reveal_controller_still_changes_the_mode() {
        setContent()

        switch().performClick()

        assertMode(ThemeMode.DARK)
    }

    /** The accessibility state is what a screen-reader user has instead of the thumb's position. */
    @Test
    fun the_control_announces_the_selected_mode() {
        setContent(ThemeState(ThemeMode.DARK, towardDark = false))

        assertMode(ThemeMode.DARK)
        // The three icons carry no descriptions of their own, so the mode is announced once.
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.theme_dark),
        ).assertDoesNotExist()
    }
}
