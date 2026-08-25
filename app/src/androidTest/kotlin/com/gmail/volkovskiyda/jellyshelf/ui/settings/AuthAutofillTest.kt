package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sign-in form's autofill behaviour: what it tells a password manager, and what it leaves
 * behind once the manager has filled it.
 *
 * A manager only offers the credential it already holds when the fields say what they are: with no
 * [SemanticsProperties.ContentType] published, the provider guesses from the masked field alone
 * and offers to generate a *new* password instead of filling the saved one.
 *
 * Asserted on the semantics rather than against a live autofill provider, which no instrumented
 * run can rely on being installed — what the form declares, and how it is composed, are the whole
 * of what this app controls.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class AuthAutofillTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    /** Held outside the composition, like the real ViewModel's state, so a test can move it. */
    private var state by mutableStateOf(SettingsUiState())

    private fun setContent(initial: SettingsUiState) {
        state = initial
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                SettingsContent(state = state, videoCount = 0, actions = SettingsActions())
            }
        }
    }

    private fun usernameFieldId() = composeRule.onNodeWithTag(USERNAME_FIELD_TAG).fetchSemanticsNode().id

    private fun assertContentType(tag: String, expected: ContentType) {
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentType, expected),
        )
    }

    private fun assertNotAutofillable(tag: String) {
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None),
        )
    }

    @Test
    fun theSignInForm_hintsUsernameAndSavedPassword() {
        setContent(SettingsUiState(serverUrl = "https://example.org"))

        assertContentType(USERNAME_FIELD_TAG, ContentType.Username)
        // Password, not NewPassword: signing in to a server that already has the account.
        assertContentType(PASSWORD_FIELD_TAG, ContentType.Password)
    }

    /**
     * The addresses on this screen have to opt *out*.
     *
     * Every Compose text field is autofillable by default, and an unhinted one is left to the
     * provider's own classifier — which, for a box sitting directly above a username and a
     * password, guessed credential: Google's autofill filled the saved password into the server
     * URL field. Neither address is anything a password manager should touch, and there is no
     * content type for "server address" to declare instead, so they declare no autofillable data
     * at all.
     */
    @Test
    fun theAddressFields_declareNothingForAPasswordManagerToFill() {
        setContent(
            SettingsUiState(serverUrl = "https://example.org", signedIn = true, username = "wolf"),
        )

        assertNotAutofillable(SERVER_URL_FIELD_TAG)
        assertNotAutofillable(INDEX_URL_FIELD_TAG)
    }

    /**
     * Signing in has to build a *new* username field rather than re-label the standing one.
     *
     * A field the manager filled paints itself with the autofill highlight and drops it only on an
     * edit the user makes — replacing the typed username with the server's is not one, so a reused
     * field stays washed yellow for as long as the screen lives. Whether it is reused is what a
     * test can see: a rebuilt field is a new node with a new semantics id.
     */
    @Test
    fun signingIn_rebuildsTheUsernameField_ratherThanRelabellingTheFilledOne() {
        setContent(SettingsUiState(serverUrl = "https://example.org", username = "wolf"))
        val filled = usernameFieldId()

        composeRule.runOnIdle { state = state.copy(signedIn = true, password = "") }

        assertNotEquals("the filled field was reused, so its highlight survives", filled, usernameFieldId())
    }
}
