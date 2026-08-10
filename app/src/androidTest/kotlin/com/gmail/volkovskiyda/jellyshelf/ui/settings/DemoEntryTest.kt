package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_USER_ID
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The demo affordances on the Settings screen, through real UI wiring: the button appears only
 * where it is useful, the caption announces the demo (and the auto-clear) once one is running, and
 * the sync-scope section — every control of which is a server request — stays away from the demo
 * server's fake user.
 *
 * [SettingsUiStateTest] pins the flags; this pins that the screen actually renders them.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class DemoEntryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Fails on unlabelled clickables, undersized targets and unreadable contrast — as elsewhere. */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private var demoTaps = 0

    private fun setContent(state: SettingsUiState) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                SettingsContent(
                    state = state,
                    videoCount = 0,
                    actions = SettingsActions(tryDemo = { demoTaps++ }),
                )
            }
        }
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    @Test
    fun tryDemo_isOfferedOnAFreshInstall() {
        setContent(SettingsUiState())

        composeRule.onNodeWithText(label(R.string.try_demo)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.demo_mode_active)).assertDoesNotExist()
    }

    @Test
    fun tryDemo_isNotOfferedOnceThereAreCredentials() {
        setContent(SettingsUiState(signedIn = true, serverUrl = "https://example.org"))

        composeRule.onNodeWithText(label(R.string.try_demo)).assertDoesNotExist()
    }

    @Test
    fun tryDemo_reachesTheAction() {
        setContent(SettingsUiState())

        composeRule.onNodeWithText(label(R.string.try_demo)).performClick()

        assertEquals(1, demoTaps)
    }

    /** In demo the button is gone — Sign out is the way out — and the caption explains why. */
    @Test
    fun inDemoMode_theCaptionReplacesTheButton() {
        setContent(SettingsUiState(demoMode = true))

        composeRule.onNodeWithText(label(R.string.demo_mode_active)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.try_demo)).assertDoesNotExist()
        // Scrolled to rather than asserted in place, so that moving a section above or below it
        // stays a layout change rather than a test failure.
        composeRule.onNodeWithText(label(R.string.sign_out))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * A demo is not signed in, so Sign in has to stay: signing in to a real server from a demo is
     * one step, and the demo caption promises exactly that ("connecting to a server clears the
     * demo data"). Sign out being the *only* button here would make it two.
     */
    @Test
    fun inDemoMode_signInIsStillOffered() {
        setContent(SettingsUiState(demoMode = true))

        composeRule.onNodeWithText(label(R.string.sign_in)).assertIsDisplayed()
    }

    @Test
    fun theDemoUser_doesNotOpenTheSyncScopeSection() {
        setContent(SettingsUiState(users = emptyList(), selectedUserId = DEMO_USER_ID))

        composeRule.onNodeWithText(label(R.string.sync_scope)).assertDoesNotExist()
    }

    /** A real selected user still does, or this test would pass by hiding the section from everyone. */
    @Test
    fun aRealUser_stillOpensTheSyncScopeSection() {
        setContent(SettingsUiState(selectedUserId = "user-id"))

        composeRule.onNodeWithText(label(R.string.sync_scope)).assertIsDisplayed()
    }
}
