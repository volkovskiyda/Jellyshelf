package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
 * Comfortably past the 3 s a failure stays up, so a slow device does not fail the test that waits
 * for it — and long enough to be a real assertion in the one that waits *through* it.
 */
private const val FAILURE_TIMEOUT_MS = 5_000L

/** A swipe and its dismissal animation; nothing here is waiting on a timer. */
private const val SWIPE_TIMEOUT_MS = 2_000L

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
                Scaffold(
                    snackbarHost = { InstallSnackbarHost(hostState, current) },
                ) { padding ->
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

    /**
     * The percentage climbs inside the snackbar that is already up.
     *
     * The label is read from live state rather than from the visuals the snackbar was shown with,
     * so each tick recomposes one `Text` instead of dismissing and re-animating the whole thing.
     * [InstallSnackbarKeyTest][com.gmail.volkovskiyda.jellyshelf.ui.InstallSnackbarKeyTest] pins
     * the key that makes that true; this pins that the rendered text follows anyway.
     */
    @Test
    fun successiveTicks_updateTheLabelInPlace() {
        setContent(InstallState.Running(InstallStage.DOWNLOADING, 128, 1024))

        listOf(256L, 512L, 768L).forEach { bytes ->
            composeRule.runOnIdle {
                state.value = InstallState.Running(InstallStage.DOWNLOADING, bytes, 1024)
            }
        }

        composeRule.onNodeWithText(label(R.string.update_install_downloading, 75)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.update_install_downloading, 12)).assertDoesNotExist()
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

    /**
     * A failure states its reason and then clears itself, rather than sitting there until dealt
     * with. Real time, because the timeout is a coroutine delay rather than a frame-clock effect.
     */
    @Test
    fun aFailure_clearsItselfWithoutBeingTouched() {
        setContent(InstallState.Failed(UpdateCheckError.DownloadFailed))
        val message = label(UpdateCheckError.DownloadFailed.messageRes)
        composeRule.onNodeWithText(message).assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = FAILURE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty()
        }

        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    /**
     * Progress does *not* clear itself: it has to outlast a download, however long that takes.
     *
     * Asserted past the failure timeout, so a regression that gave both the same duration fails
     * here rather than only showing up as a vanished progress line on a slow connection.
     */
    @Test
    fun progress_staysUpPastTheFailureTimeout() {
        setContent(InstallState.Running(InstallStage.INSTALLING))

        Thread.sleep(FAILURE_TIMEOUT_MS)

        composeRule.onNodeWithText(label(R.string.update_install_installing)).assertIsDisplayed()
    }

    /**
     * The way out of a progress snackbar that never resolves.
     *
     * An install whose `UpdateTask` neither succeeds nor fails leaves an indefinite snackbar
     * carrying no action at all — observed on device. A swipe dismisses it like any other.
     */
    @Test
    fun progress_canBeSwipedAway() {
        setContent(InstallState.Running(InstallStage.INSTALLING))
        val message = label(R.string.update_install_installing)

        composeRule.onNodeWithText(message).performTouchInput { swipeRight() }

        composeRule.waitUntil(timeoutMillis = SWIPE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Swiping a failure away counts as dismissing it, so the state clears upstream too. */
    @Test
    fun swipingAFailureAway_reportsIt() {
        setContent(InstallState.Failed(UpdateCheckError.InstallCancelled))

        composeRule.onNodeWithText(label(UpdateCheckError.InstallCancelled.messageRes))
            .performTouchInput { swipeRight() }

        composeRule.waitUntil(timeoutMillis = SWIPE_TIMEOUT_MS) { dismissals == 1 }
    }
}
