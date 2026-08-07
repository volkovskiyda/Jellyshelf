package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Updates section says after a check, rendered through the real settings layout.
 *
 * This is the seam that makes the section testable at all: the whole feature is dark on a debug
 * build — [UpdateChecker][com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker] returns before
 * doing anything, the section is hidden, and the App Distribution SDK is the API-only stub — and
 * instrumented tests run the debug variant. `SettingsContent` is a plain function of the state it
 * is handed and `isDebugBuild` defaults to false, so the section renders here exactly as a release
 * build draws it, with no DI override and nothing mocked.
 *
 * [UpdateCheckerTest][com.gmail.volkovskiyda.jellyshelf.domain.UpdateCheckerTest] decides *when*
 * each of these states is produced; this pins that each one reaches the screen.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class UpdatesSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Fails on unlabelled clickables, undersized targets and unreadable contrast — as elsewhere. */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun setContent(state: SettingsUiState) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                SettingsContent(state = state, videoCount = 0, actions = SettingsActions())
            }
        }
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    /** The section sits above the fold on this screen, so every assertion scrolls to it first. */
    private fun assertShows(resId: Int) =
        composeRule.onNodeWithText(label(resId)).performScrollTo().assertIsDisplayed()

    @Test
    fun aCheckThatFoundNothing_saysSo() {
        setContent(SettingsUiState(updateSource = UpdateSource.GITHUB, upToDate = true))

        assertShows(R.string.update_up_to_date)
    }

    /**
     * The state before anyone has tapped anything. Without this the previous test would pass on a
     * screen that always shows the line, which says nothing at all.
     */
    @Test
    fun beforeAnyCheck_theScreenClaimsNothing() {
        setContent(SettingsUiState(updateSource = UpdateSource.GITHUB))

        composeRule.onNodeWithText(label(R.string.update_up_to_date)).assertDoesNotExist()
        assertShows(R.string.update_never_checked)
    }

    /** A failure is the other outcome, and the two must never be on screen together. */
    @Test
    fun aFailedCheck_showsTheReasonAndNoReassurance() {
        setContent(
            SettingsUiState(
                updateSource = UpdateSource.GITHUB,
                updateError = UpdateCheckError.Network,
            ),
        )

        assertShows(UpdateCheckError.Network.messageRes)
        composeRule.onNodeWithText(label(R.string.update_up_to_date)).assertDoesNotExist()
    }

    /** The whole section is a release-build affordance and stays hidden on debug. */
    @Test
    fun aDebugBuild_hasNoUpdatesSectionAtAll() {
        setContent(SettingsUiState(isDebugBuild = true, upToDate = true))

        composeRule.onNodeWithText(label(R.string.updates)).assertDoesNotExist()
        composeRule.onNodeWithText(label(R.string.check_for_updates)).assertDoesNotExist()
        composeRule.onNodeWithText(label(R.string.update_up_to_date)).assertDoesNotExist()
    }
}
