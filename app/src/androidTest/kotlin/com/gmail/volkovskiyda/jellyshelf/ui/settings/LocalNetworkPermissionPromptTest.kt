package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the local-network rationale is hosted at all, and that declining closes it without reaching
 * Android's own dialog.
 *
 * The dialog itself is deliberately not tested twice over: it is
 * `LocalNetworkPermissionDialog`, a near-copy of the notification rationale whose buttons and
 * dismiss-is-a-decline rule `NotificationPermissionDialogTest` already pins down. What is new here
 * is the hosting decision — four gates, each of which silences it.
 *
 * Every gate is a parameter here on purpose. The platform answer arrives through the
 * `readPermission` seam because it cannot be staged on a device — `pm revoke` kills the
 * instrumented process along with the app — and the API level arrives through `permissionExists`
 * because the suite runs on API 31 as well as on hardware new enough to have the permission at all.
 * With both passed explicitly the composable touches no Koin container, which is what lets this run
 * against a plain [ComponentActivity].
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionPromptTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(
        signedIn: Boolean = false,
        permissionExists: Boolean = true,
        granted: Boolean = false,
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                LocalNetworkPermissionPrompt(
                    signedIn = signedIn,
                    permissionExists = permissionExists,
                    readPermission = { granted },
                )
            }
        }
    }

    private fun title() =
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.local_network_permission_title))

    /** The one case it exists for: a device that has the permission and has not granted it. */
    @Test
    fun a_missing_permission_is_explained_before_it_is_requested() {
        setContent()

        title().assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.local_network_permission_body),
        ).assertIsDisplayed()
    }

    /**
     * The ordinary case, and the one that must stay silent: the manifest declaration means the
     * platform hands this out at install, so almost every real launch arrives here already granted.
     */
    @Test
    fun a_granted_permission_prompts_for_nothing() {
        setContent(granted = true)

        title().assertDoesNotExist()
    }

    /**
     * A session already exists, so the connection this permission gates has demonstrably worked —
     * there is nothing left to warn about and no sign-in to save.
     */
    @Test
    fun a_signed_in_app_prompts_for_nothing() {
        setContent(signedIn = true)

        title().assertDoesNotExist()
    }

    /** Below API 36 local addresses need no extra grant, so there is nothing to ask for. */
    @Test
    fun a_platform_without_the_permission_prompts_for_nothing() {
        setContent(permissionExists = false)

        title().assertDoesNotExist()
    }

    /**
     * "Not now" closes it and keeps it closed for this visit. Deliberately not persisted — the next
     * launch of an app that still cannot reach its server is a launch where the question is worth
     * putting again — so this asserts the visit, not forever.
     */
    @Test
    fun declining_closes_the_prompt_without_reaching_android() {
        setContent()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.local_network_permission_dismiss),
        ).performClick()

        title().assertDoesNotExist()
    }
}
