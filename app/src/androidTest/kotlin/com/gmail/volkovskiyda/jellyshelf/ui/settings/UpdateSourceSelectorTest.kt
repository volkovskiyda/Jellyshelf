package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The update-channel picker and the controls beside it.
 *
 * Drives the **stateless** selector rather than `SettingsContent`, which matters here: the section
 * hosting it is hidden in a debug build, and these tests run on the debug variant. Keeping the
 * control free of any build-type knowledge is what makes it testable at all.
 *
 * Instrumented, per this project's no-Robolectric convention; `connectedDebugAndroidTest` skips
 * itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class UpdateSourceSelectorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Fails on unlabelled clickables, undersized touch targets and unreadable contrast. The touch
     * target is the reason this is not optional: a bare FilterChip is shorter than the Material
     * minimum, which is exactly the mistake [ThemeModeSwitchTest] exists to have caught once.
     */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private var selections = mutableListOf<UpdateSource>()

    private fun setContent(
        initial: UpdateSource = UpdateSource.NONE,
        signingIn: Boolean = false,
        error: UpdateCheckError? = null,
    ) {
        // Held outside the composition the way the real ViewModel holds it: created inside, it
        // would reset on every recomposition and no click would ever stick.
        var selected by mutableStateOf(initial)
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                Column {
                    UpdateSourceSelector(
                        selected = selected,
                        onSelect = {
                            selections += it
                            selected = it
                        },
                        signingIn = signingIn,
                    )
                    // The two controls that sit with the selector and share its enablement rules.
                    OutlinedButton(
                        onClick = {},
                        enabled = selected != UpdateSource.NONE && !signingIn,
                    ) { Text(stringResource(R.string.check_for_updates)) }
                    error?.let { Text(stringResource(it.messageRes)) }
                }
            }
        }
    }

    private fun chip(source: UpdateSource) =
        composeRule.onNodeWithText(composeRule.activity.getString(source.labelRes))

    private fun checkNow() =
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.check_for_updates))

    private fun assertAnnounced(source: UpdateSource) {
        val expected = composeRule.activity.getString(
            R.string.update_source_selected,
            composeRule.activity.getString(source.labelRes),
        )
        composeRule.onNode(hasStateDescription(expected)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected),
        )
    }

    /** Every channel is directly reachable — unlike the theme switch, this is a pick, not a walk. */
    @Test
    fun each_channel_can_be_picked_directly() {
        setContent()

        chip(UpdateSource.APP_DISTRIBUTION).performClick()
        chip(UpdateSource.GITHUB).performClick()

        assertEquals(
            listOf(UpdateSource.APP_DISTRIBUTION, UpdateSource.GITHUB),
            selections,
        )
        chip(UpdateSource.GITHUB).assertIsSelected()
    }

    @Test
    fun a_pick_is_reported_once() {
        setContent()

        chip(UpdateSource.GITHUB).performClick()

        assertEquals(listOf(UpdateSource.GITHUB), selections)
    }

    /**
     * Visible but disabled when nothing is selected: the feature has to be discoverable before
     * opting in, and a control that appears only afterwards explains nothing.
     */
    @Test
    fun check_now_is_visible_but_disabled_until_a_channel_is_picked() {
        setContent()

        checkNow().assertIsNotEnabled()

        chip(UpdateSource.GITHUB).performClick()

        checkNow().assertIsEnabled()
    }

    /** The Custom Tab is a separate task, so the user can come back and must not start a second. */
    @Test
    fun a_sign_in_in_flight_disables_everything() {
        setContent(initial = UpdateSource.GITHUB, signingIn = true)

        chip(UpdateSource.NONE).assertIsNotEnabled()
        chip(UpdateSource.GITHUB).assertIsNotEnabled()
        chip(UpdateSource.APP_DISTRIBUTION).assertIsNotEnabled()
        checkNow().assertIsNotEnabled()
    }

    /** A failed check says why. The whole point of the error type is that this is never silent. */
    @Test
    fun a_failure_renders_its_reason() {
        setContent(initial = UpdateSource.APP_DISTRIBUTION, error = UpdateCheckError.ApiDisabled)

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.update_error_api_disabled),
        ).assertExists()
    }

    /** What a screen-reader user has instead of the filled chip. */
    @Test
    fun the_group_announces_the_selected_channel() {
        setContent(initial = UpdateSource.GITHUB)

        assertAnnounced(UpdateSource.GITHUB)

        chip(UpdateSource.APP_DISTRIBUTION).performClick()

        assertAnnounced(UpdateSource.APP_DISTRIBUTION)
    }
}
