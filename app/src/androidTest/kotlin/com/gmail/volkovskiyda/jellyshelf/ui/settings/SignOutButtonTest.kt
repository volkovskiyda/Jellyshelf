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
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Who is offered Sign out, and what it takes to fire it.
 *
 * Sign out is now the only destructive action on the Settings screen — it drops the credential
 * *and* wipes the library, which is what "Reset local data" used to do on its own. That makes two
 * things worth pinning here rather than in a ViewModel test: every path that has data to lose can
 * reach the button, and no path reaches the wipe without confirming it.
 *
 * [SignOutInstrumentedTest] covers what the action then does; this is only the affordance.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class SignOutButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Fails on unlabelled clickables, undersized targets and unreadable contrast — as elsewhere. */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private var signOuts = 0

    private fun setContent(state: SettingsUiState) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                SettingsContent(
                    state = state,
                    videoCount = 0,
                    actions = SettingsActions(signOut = { signOuts++ }),
                )
            }
        }
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    /** Nothing configured, nothing to lose: the form offers only a way *in*. */
    @Test
    fun aFreshInstall_isNotOfferedSignOut() {
        setContent(SettingsUiState())

        composeRule.onNodeWithText(label(R.string.sign_out)).assertDoesNotExist()
    }

    /** Signed in, the two are one control: there is nothing left to sign in to. */
    @Test
    fun signedIn_replacesSignInWithSignOut() {
        setContent(SettingsUiState(signedIn = true, serverUrl = "https://example.org"))

        composeRule.onNodeWithText(label(R.string.sign_out)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.sign_in)).assertDoesNotExist()
    }

    /**
     * The API-key path never signs in, so without this it would have had no way to clear its
     * library once "Reset local data" went away — the gap this flag exists to close.
     */
    @Test
    fun aPersistedApiKey_offersSignOut() {
        setContent(SettingsUiState(apiKey = "key", apiKeyConnected = true))

        composeRule.onNodeWithText(label(R.string.sign_out)).performScrollTo().assertIsDisplayed()
    }

    /**
     * …but the *field* holding text is not enough. Reading it directly would swap the button out
     * from under someone halfway through typing a key they have not connected with yet.
     */
    @Test
    fun aTypedButUnsavedApiKey_doesNot() {
        setContent(SettingsUiState(apiKey = "key"))

        composeRule.onNodeWithText(label(R.string.sign_out)).assertDoesNotExist()
    }

    /** The whole point of the confirmation: the tap opens a dialog, it does not wipe anything. */
    @Test
    fun theButtonAlone_doesNotSignOut() {
        setContent(SettingsUiState(signedIn = true))

        composeRule.onNodeWithText(label(R.string.sign_out)).performScrollTo().performClick()

        composeRule.onNodeWithText(label(R.string.sign_out_dialog_title)).assertIsDisplayed()
        assertEquals("the dialog must be answered first", 0, signOuts)
    }

    @Test
    fun cancelling_leavesEverythingAlone() {
        setContent(SettingsUiState(signedIn = true))

        composeRule.onNodeWithText(label(R.string.sign_out)).performScrollTo().performClick()
        composeRule.onNodeWithText(label(R.string.cancel)).performClick()

        composeRule.onNodeWithText(label(R.string.sign_out_dialog_title)).assertDoesNotExist()
        assertEquals(0, signOuts)
    }

    /**
     * Confirmed from the demo, whose dialog is worded for what a demo actually loses — and whose
     * confirm button is the one place the two labels differ, so this also pins that the demo
     * wording reaches the same action.
     */
    @Test
    fun confirmingFromTheDemo_signsOut() {
        setContent(SettingsUiState(demoMode = true))

        composeRule.onNodeWithText(label(R.string.sign_out)).performScrollTo().performClick()
        composeRule.onNodeWithText(label(R.string.sign_out_dialog_title_demo)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.leave_demo)).performClick()

        assertEquals(1, signOuts)
    }
}
