package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.NotificationPermissionRule
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateCheckFailure
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.settings.messageRes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

private const val INSTALLED_VERSION_CODE = 100
private const val OFFERED_VERSION_CODE = 200
private const val OFFERED_VERSION_NAME = "1.0.200"

/** See [UpdateOfferHostingTest]: the clock has to dwarf the `0`-means-never timestamps. */
private const val NOW = 1_800_000_000_000L

/**
 * Far past the 3 s a failure nominally narrates itself for. Deliberately looser than
 * [InstallProgressSnackbarTest]'s window: the interval's *length* is that test's to pin, against a
 * bare host with nothing else on the main thread. Here the timer shares the thread with the real
 * activity's first composition — sign-in screen, navigation, the dialog tearing down — and on a
 * device that measured well past 5 s. This test only pins that the round trip completes.
 */
/** See [InstallFlowTest.offerAndAccept] — the offer dialog is a window, and lands late. */
private const val OFFER_TIMEOUT_MS = 5_000L

private const val FAILURE_TIMEOUT_MS = 15_000L

/**
 * The last unwired stretch of the update feature: that tapping **Update** in the real offer dialog
 * starts the snackbar narration, and that dismissal of a failure flows back into the checker.
 *
 * [InstallProgressSnackbarTest] proves the effect and the host against a bare `Scaffold`;
 * [UpdateCheckerTest][com.gmail.volkovskiyda.jellyshelf.domain.UpdateCheckerTest] proves the state
 * machine. Neither proves `MainActivity` connects them — that the dialog's button reaches
 * `install()`, that the host at the `Scaffold` actually renders `installState`, and that
 * `onFailureDismissed` is wired to `clearInstallState` rather than to nothing.
 *
 * Same seam as [UpdateOfferHostingTest], and for the same reasons documented there: a replaced
 * [UpdateChecker] single lifts the debug gates, the offer arrives from the App Distribution
 * channel so "Update" installs in-app instead of linking out to a browser, and the stubbed
 * `install` produces the progress the real SDK only reports mid-download.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class InstallFlowTest {

    /** What the stubbed download does when asked; each test sets its own before overriding. */
    private var installBehaviour: suspend ((InstallState.Running) -> Unit) -> Unit = {}

    /** Held open by the progress test, released in [restoreTheRealChecker] so nothing leaks. */
    private val installGate = CompletableDeferred<Unit>()

    private val appDistribution = object : AppDistributionSource(InertTesterSignIn, FakeUpdateFlags()) {
        override fun isTesterSignedIn() = true
        override suspend fun latestRelease() = UpdateInfo(
            versionCode = OFFERED_VERSION_CODE,
            versionName = OFFERED_VERSION_NAME,
            releaseNotes = "",
            downloadUrl = "",
            source = UpdateSource.APP_DISTRIBUTION,
        )

        override suspend fun install(onProgress: (InstallState.Running) -> Unit) =
            installBehaviour(onProgress)
    }

    private val checker = UpdateChecker(
        settingsRepository = FakeSettingsRepository(updateSource = UpdateSource.APP_DISTRIBUTION),
        gitHubSource = object : GitHubReleaseSource(HttpClient(OkHttp), TestDispatcherProvider(), Json) {
            override suspend fun latestRelease(): UpdateInfo? = null
        },
        appDistributionSource = appDistribution,
        buildInfo = BuildInfo(isDebug = false, sdkInt = 36, versionCode = INSTALLED_VERSION_CODE),
        time = TimeProvider { NOW },
        dispatchers = TestDispatcherProvider(),
        updateCheckSchedule = RecordingUpdateCheckSchedule(),
    )

    private lateinit var realChecker: UpdateChecker

    /** Granted before the activity exists — see [UpdateOfferHostingTest] for why. */
    @get:Rule(order = 0)
    val notificationPermission = NotificationPermissionRule()

    /**
     * Swaps the checker in **before the compose rule launches the activity**, and puts the real one
     * back afterwards — the shape [UpdateOfferHostingTest] uses, and for the reason it documents.
     *
     * Doing it from the test body instead is a race: `koinInject` resolves once and remembers, so
     * whichever checker the composition sees first is the one the screen keeps. Win the race and it
     * watches the fake; lose it and it watches the app's own checker, which has nothing to offer
     * and never shows a dialog — a five-second timeout with no hint of why.
     *
     * Restoring re-declares the real single rather than unloading the override, because
     * `unloadKoinModules` removes definitions by key and would take the app's own with it.
     */
    @get:Rule(order = 1)
    val koinOverride = object : ExternalResource() {
        override fun before() {
            realChecker = GlobalContext.get().get()
            loadKoinModules(module { single { checker } })
        }

        override fun after() {
            // Released first: a test holding the install open would otherwise leave the fake's
            // coroutine parked on a gate nothing completes.
            installGate.complete(Unit)
            loadKoinModules(module { single { realChecker } })
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun label(resId: Int, vararg args: Any) = composeRule.activity.getString(resId, *args)

    /**
     * Raises the real offer dialog and taps **Update**.
     *
     * A *requested* check, deliberately: a signed-out install starts on the sign-in screen rather
     * than the Library tab, so an automatic offer would wait for a tab this test never visits.
     * Where each kind of offer may appear is [UpdateOfferHostingTest]'s subject; this class only
     * needs a dialog, and a requested one shows wherever the app happens to be.
     */
    private fun offerAndAccept() {
        runBlocking { checker.checkNow() }
        composeRule.waitForIdle()
        // Waited for rather than asserted outright, for the reason [UpdateOfferHostingTest] gives:
        // the dialog is a *window*, and `waitForIdle` returns once the composition that asked for
        // it has settled — a beat before that window is attached and laid out. A bare
        // `assertIsDisplayed` cannot tell "not there" from "not there yet", so it turns a few
        // hundred milliseconds of device speed into a failure with a misleading message.
        composeRule.waitUntil(OFFER_TIMEOUT_MS) {
            composeRule.onNodeWithText(label(R.string.update_available_title, OFFERED_VERSION_NAME))
                .isDisplayed()
        }
        composeRule.onNodeWithText(label(R.string.update_available_title, OFFERED_VERSION_NAME))
            .assertIsDisplayed()

        composeRule.onNodeWithText(label(R.string.update_install)).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun tappingUpdate_startsTheSnackbarNarration() {
        installBehaviour = { onProgress ->
            onProgress(InstallState.Running(InstallStage.DOWNLOADING, 512, 1024))
            // Held mid-download, the way a real transfer spends most of its time — the snackbar
            // has to be up *while* the install runs, not after it resolves.
            installGate.await()
        }
        offerAndAccept()

        composeRule.onNodeWithText(label(R.string.update_install_downloading, 50)).assertIsDisplayed()
        // The dialog is gone the moment the choice is made; only the snackbar narrates from here.
        composeRule.onNodeWithText(label(R.string.update_available_title, OFFERED_VERSION_NAME))
            .assertDoesNotExist()
    }

    /**
     * The failure round trip: the reason surfaces where the user is, and its self-dismissal calls
     * back into the checker — [UpdateChecker.installState] returning to null is the proof that
     * `onFailureDismissed` is wired to `clearInstallState` and not just to closing the snackbar.
     */
    @Test
    fun aFailedInstall_saysWhyThenClearsAllTheWayBack() {
        installBehaviour = { throw UpdateCheckFailure(UpdateCheckError.DownloadFailed, "", null) }
        offerAndAccept()

        val message = label(UpdateCheckError.DownloadFailed.messageRes)
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.waitUntil(FAILURE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty()
        }
        composeRule.runOnIdle { assertNull(checker.installState.value) }
    }
}
