package com.gmail.volkovskiyda.jellyshelf.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The update offer's two answers, and that every way out except "Update" counts as the same "not
 * now" — including a tap outside and Back. A dialog that came back on the next launch because the
 * user tapped beside it is exactly the nagging the snooze exists to bound.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private var updates = 0
    private var dismissals = 0

    private fun info(notes: String) = UpdateInfo(
        versionCode = 181,
        versionName = "1.1",
        releaseNotes = notes,
        downloadUrl = "https://example.invalid/jellyshelf-1.1.181.apk",
        source = UpdateSource.GITHUB,
    )

    private fun setContent(notes: String = "Some release notes.") {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                UpdateDialog(
                    info = info(notes),
                    onUpdate = { updates++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
    }

    private fun button(resId: Int) =
        composeRule.onNodeWithText(composeRule.activity.getString(resId))

    @Test
    fun the_offer_names_the_version() {
        setContent()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.update_available_title, "1.1"),
        ).assertIsDisplayed()
    }

    @Test
    fun not_now_dismisses_once() {
        setContent()

        button(R.string.update_dismiss).performClick()

        assertEquals(1, dismissals)
        assertEquals(0, updates)
    }

    /**
     * Back takes the same path as "Not now", or the offer would return on the next launch.
     *
     * Sent as a real key event rather than through the activity's `onBackPressedDispatcher`: a
     * dialog lives in its own window, so the activity's dispatcher never sees it — that is exactly
     * what an earlier version of this test proved by failing.
     */
    @Test
    fun back_counts_as_not_now() {
        setContent()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        assertEquals(0, updates)
    }

    /**
     * Choosing to update is deliberately *not* a dismissal: an abandoned download or a failed
     * install must let the prompt return on the next check rather than snooze it for a week.
     */
    @Test
    fun update_reports_an_update_and_no_dismissal() {
        setContent()

        button(R.string.update_install).performClick()

        assertEquals(1, updates)
        assertEquals(0, dismissals)
    }

    /** Long notes must scroll inside the dialog rather than push its buttons off the screen. */
    @Test
    fun long_notes_leave_both_buttons_reachable() {
        setContent(notes = (1..60).joinToString("\n") { "• Release note number $it" })

        button(R.string.update_dismiss).assertIsDisplayed()
        button(R.string.update_install).assertIsDisplayed()
    }

    /** A release with no notes is still an update; an empty panel would read as a failed load. */
    @Test
    fun empty_notes_render_no_body() {
        setContent(notes = "")

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.update_available_title, "1.1"),
        ).assertIsDisplayed()
        button(R.string.update_install).assertIsDisplayed()
        button(R.string.update_dismiss).assertIsDisplayed()
    }
}
