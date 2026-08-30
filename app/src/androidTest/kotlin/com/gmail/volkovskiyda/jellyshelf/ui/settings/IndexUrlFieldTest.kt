package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The metadata index URL field's three states, through real UI wiring: absent before there are
 * credentials, open with **Fill** before the first sync, and locked afterwards until deliberately
 * unlocked. [SettingsUiStateTest] pins the flags themselves; what this adds is that the lock
 * actually blocks typing rather than only looking closed.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class IndexUrlFieldTest {

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

    private var edits = 0

    private fun setContent(initial: SettingsUiState) {
        // Held outside the composition, like the real ViewModel's state: created inside, typing
        // would never stick and the test would pass for the wrong reason.
        var state by mutableStateOf(initial)
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                SettingsContent(
                    state = state,
                    videoCount = 0,
                    actions = SettingsActions(
                        onIndexUrlChange = {
                            edits++
                            state = state.copy(indexUrl = it)
                        },
                    ),
                    // The field lives inside the Advanced section now; expanded up front, since
                    // what these tests pin is the field's behavior, not the expander's.
                    advancedExpanded = true,
                )
            }
        }
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    private val signedInBeforeSync = SettingsUiState(
        serverUrl = "https://example.org",
        signedIn = true,
        selectedUserId = "user-id",
    )

    private val signedInAfterSync = signedInBeforeSync.copy(lastSyncAt = 1_785_143_919_405L)

    @Test
    fun theField_isAbsentBeforeThereAreCredentials() {
        setContent(SettingsUiState(serverUrl = "https://example.org"))

        composeRule.onNodeWithText(label(R.string.index_url_label)).assertDoesNotExist()
    }

    @Test
    fun theField_appearsWithFill_onceSignedInAndBeforeAnySync() {
        setContent(signedInBeforeSync)

        composeRule.onNodeWithText(label(R.string.index_url_label)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.fill)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.unlock_index_url)).assertDoesNotExist()
    }

    @Test
    fun theField_acceptsTypingBeforeTheFirstSync() {
        setContent(signedInBeforeSync)

        composeRule.onNodeWithText(label(R.string.index_url_label)).performScrollTo().performTextInput("x")

        assertEquals(1, edits)
    }

    @Test
    fun aSyncedField_locksAndSwapsFillForTheLockButton() {
        setContent(signedInAfterSync)

        composeRule.onNodeWithContentDescription(label(R.string.unlock_index_url))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.fill)).assertDoesNotExist()
    }

    /**
     * A locked field offers no way to set text at all — asserted on the semantics rather than by
     * typing into it, because `performTextInput` throws on a node with no `SetText` action, which
     * would pass the test by crashing rather than by the lock holding.
     */
    private fun assertNotTypeable() {
        composeRule.onNodeWithText(label(R.string.index_url_label))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        assertEquals("a locked field accepted an edit", 0, edits)
    }

    @Test
    fun aLockedField_refusesTyping() {
        setContent(signedInAfterSync)

        assertNotTypeable()
    }

    @Test
    fun unlocking_letsTypingThrough_andOffersToLockAgain() {
        setContent(signedInAfterSync)

        composeRule.onNodeWithContentDescription(label(R.string.unlock_index_url))
            .performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(label(R.string.lock_index_url)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.index_url_label)).performTextInput("x")

        assertEquals(1, edits)
    }

    @Test
    fun lockingAgain_stopsTypingAgain() {
        setContent(signedInAfterSync)

        composeRule.onNodeWithContentDescription(label(R.string.unlock_index_url))
            .performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(label(R.string.lock_index_url)).performClick()

        assertNotTypeable()
    }
}
