package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.ui.settings.messageRes
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the install snackbar says, and what dismissing it does.
 *
 * Rendered through a bare `Scaffold` rather than the whole app: the effect and the host are the
 * unit under test, and [UpdateOfferHostingTest] already covers that `MainActivity` wires them
 * together. `installState` is driven by hand here because the SDK's progress callbacks cannot be
 * produced on a device without an actual App Distribution download.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class InstallProgressSnackbarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0
    private lateinit var state: MutableState<InstallState?>

    private fun setContent(initial: InstallState?) {
        composeRule.setContent {
            state = remember { mutableStateOf(initial) }
            val current by state
            JellyshelfTheme(dynamicColor = false) {
                val hostState = remember { SnackbarHostState() }
                InstallProgressEffect(
                    state = current,
                    hostState = hostState,
                    onFailureDismissed = { dismissals++ },
                )
                // The empty body still has to consume the content padding, or lint reads the
                // Scaffold as misused — there is simply nothing here to lay out but the host.
                Scaffold(snackbarHost = { SnackbarHost(hostState) }) { padding ->
                    Box(Modifier.padding(padding))
                }
            }
        }
    }

    private fun label(resId: Int, vararg args: Any) = composeRule.activity.getString(resId, *args)

    @Test
    fun preparing_saysSoWithNoNumber() {
        setContent(InstallState.Running(InstallStage.PREPARING))

        composeRule.onNodeWithText(label(R.string.update_install_preparing)).assertIsDisplayed()
    }

    @Test
    fun downloading_showsThePercentage() {
        setContent(InstallState.Running(InstallStage.DOWNLOADING, bytesDownloaded = 512, totalBytes = 1024))

        composeRule.onNodeWithText(label(R.string.update_install_downloading, 50)).assertIsDisplayed()
    }

    /** Before the transfer starts the SDK reports a zero total, and "0%" would look stuck. */
    @Test
    fun downloadingWithNoKnownSize_claimsNoPercentage() {
        setContent(InstallState.Running(InstallStage.DOWNLOADING))

        composeRule.onNodeWithText(label(R.string.update_install_downloading_unknown))
            .assertIsDisplayed()
    }

    @Test
    fun installing_saysSo() {
        setContent(InstallState.Running(InstallStage.INSTALLING))

        composeRule.onNodeWithText(label(R.string.update_install_installing)).assertIsDisplayed()
    }

    /** The message has to change as the install moves on, not stay on the first stage shown. */
    @Test
    fun aStageChange_replacesTheMessage() {
        setContent(InstallState.Running(InstallStage.PREPARING))

        composeRule.runOnIdle {
            state.value = InstallState.Running(InstallStage.DOWNLOADING, 256, 1024)
        }

        composeRule.onNodeWithText(label(R.string.update_install_downloading, 25)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.update_install_preparing)).assertDoesNotExist()
    }

    /** A success clears the state, and the snackbar has to go with it. */
    @Test
    fun clearingTheState_takesTheSnackbarAway() {
        setContent(InstallState.Running(InstallStage.INSTALLING))

        composeRule.runOnIdle { state.value = null }

        composeRule.onNodeWithText(label(R.string.update_install_installing)).assertDoesNotExist()
    }

    /** A failure is a sentence the user can read, not a silently vanishing progress line. */
    @Test
    fun aFailure_showsItsReason() {
        setContent(InstallState.Failed(UpdateCheckError.DownloadFailed))

        composeRule.onNodeWithText(label(UpdateCheckError.DownloadFailed.messageRes))
            .assertIsDisplayed()
    }

    /** And only a failure is dismissible — dismissing it is what clears it upstream. */
    @Test
    fun dismissingAFailure_reportsIt() {
        setContent(InstallState.Failed(UpdateCheckError.InstallFailed))

        composeRule.onNodeWithText(label(R.string.dismiss)).performClick()

        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }
}
