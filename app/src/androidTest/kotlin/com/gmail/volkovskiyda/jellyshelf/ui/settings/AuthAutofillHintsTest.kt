package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sign-in form's autofill hints. A password manager only offers the credential it already
 * holds when the fields say what they are: with no [SemanticsProperties.ContentType] published,
 * the provider guesses from the masked field alone and offers to generate a *new* password
 * instead of filling the saved one.
 *
 * Asserted on the semantics rather than against a live autofill provider, which no instrumented
 * run can rely on being installed — the hints are the whole of what this app controls.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class AuthAutofillHintsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

    private fun assertContentType(tag: String, expected: ContentType) {
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentType, expected),
        )
    }

    @Test
    fun theSignInForm_hintsUsernameAndSavedPassword() {
        setContent(SettingsUiState(serverUrl = "https://example.org"))

        assertContentType(USERNAME_FIELD_TAG, ContentType.Username)
        // Password, not NewPassword: signing in to a server that already has the account.
        assertContentType(PASSWORD_FIELD_TAG, ContentType.Password)
    }
}
