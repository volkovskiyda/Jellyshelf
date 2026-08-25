package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The version line at the foot of Settings — the one thing on the screen that describes the APK
 * rather than the server.
 *
 * Its point is that a user can name the build they are running when reporting something, so the
 * two failures worth pinning are the ones that make it useless: not being there in a debug build
 * (where the whole Updates section above it *is* hidden — see [UpdatesSectionTest]), and printing
 * a bare "Version " when nothing supplied one.
 */
@RunWith(AndroidJUnit4::class)
class AppVersionLineTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Fails on unlabelled clickables, undersized targets and unreadable contrast — as elsewhere. */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun setContent(state: SettingsUiState) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                SettingsContent(state = state, videoCount = 0, actions = SettingsActions())
            }
        }
    }

    private fun versionLabel(version: String) = composeRule.activity.getString(R.string.app_version, version)

    /** Last on a screen taller than the viewport, so the assertion has to scroll to it. */
    @Test
    fun theInstalledVersion_isPrintedAtTheBottom() {
        setContent(SettingsUiState(versionName = VERSION))

        composeRule.onNodeWithText(versionLabel(VERSION)).performScrollTo().assertIsDisplayed()
    }

    /**
     * A debug install hides the Updates section, and this line must not go with it: reporting a bug
     * against a debug build is exactly when naming the build matters.
     */
    @Test
    fun aDebugBuild_stillNamesItsVersion() {
        setContent(SettingsUiState(isDebugBuild = true, versionName = DEBUG_VERSION))

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.updates)).assertDoesNotExist()
        composeRule.onNodeWithText(versionLabel(DEBUG_VERSION)).performScrollTo().assertIsDisplayed()
    }

    /** Nothing to name means no line, rather than the word "Version" followed by a blank. */
    @Test
    fun withNoVersion_thereIsNoLine() {
        setContent(SettingsUiState())

        composeRule.onNodeWithText(versionLabel(""), substring = true).assertDoesNotExist()
    }

    private companion object {
        const val VERSION = "1.0.294"
        const val DEBUG_VERSION = "1.0-debug"
    }
}
