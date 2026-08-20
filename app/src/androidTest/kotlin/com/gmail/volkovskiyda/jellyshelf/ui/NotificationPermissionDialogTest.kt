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
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rationale dialog's two answers, and that every way out except "Allow" is a decline —
 * including a tap outside and Back.
 *
 * The distinction matters more here than in [UpdateDialogTest]: only "Allow" reaches Android's own
 * dialog, and Android permanently locks `POST_NOTIFICATIONS` after two denials. A stray Back that
 * leaked through to the system request would spend one of them.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private var allows = 0
    private var dismissals = 0

    private fun setContent() {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                NotificationPermissionDialog(
                    onAllow = { allows++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
    }

    private fun button(resId: Int) =
        composeRule.onNodeWithText(composeRule.activity.getString(resId))

    /** The ask arrives before anything has played, so the body has to carry the whole reason. */
    @Test
    fun the_dialog_explains_what_the_permission_is_for() {
        setContent()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.notification_permission_title),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.notification_permission_body),
        ).assertIsDisplayed()
    }

    @Test
    fun allow_reports_an_allow_and_no_decline() {
        setContent()

        button(R.string.notification_permission_allow).performClick()

        assertEquals(1, allows)
        assertEquals(0, dismissals)
    }

    @Test
    fun not_now_declines_once() {
        setContent()

        button(R.string.notification_permission_dismiss).performClick()

        assertEquals(1, dismissals)
        assertEquals(0, allows)
    }

    /**
     * Back is a decline, not a silent pass-through to the system request.
     *
     * Sent as a real key event rather than through the activity's `onBackPressedDispatcher`: a
     * dialog lives in its own window, so the activity's dispatcher never sees it.
     */
    @Test
    fun back_counts_as_not_now() {
        setContent()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        assertEquals(0, allows)
    }
}
