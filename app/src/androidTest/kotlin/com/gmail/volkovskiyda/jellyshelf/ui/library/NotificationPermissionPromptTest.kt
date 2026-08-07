package com.gmail.volkovskiyda.jellyshelf.ui.library

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.NotificationPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.grantNotificationPermission
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeUpdateFlags
import com.gmail.volkovskiyda.jellyshelf.ui.InertTesterSignIn
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.ui.inertUpdateChecker
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** A plausible instant, for the same reason as everywhere else: `0` must read as "never". */
private const val NOW = 1_800_000_000_000L

/** Past the week-long snooze, so a stored answer is stale rather than fresh. */
private val EIGHT_DAYS = TimeUnit.DAYS.toMillis(8)

private const val WAIT_MS = 5_000L

/**
 * What the library-screen host does with [NotificationPrompt]'s answers — the glue that
 * [NotificationPromptTest][com.gmail.volkovskiyda.jellyshelf.domain.NotificationPromptTest] (the
 * policy) and [NotificationPermissionDialogTest][com.gmail.volkovskiyda.jellyshelf.ui.NotificationPermissionDialogTest]
 * (the dialog) leave uncovered between them: when the dialog is hosted at all, and what each
 * button writes back.
 *
 * The platform's two answers arrive through the `readPermission` seam, because they cannot be
 * staged on a device: earlier tests in any full-suite run grant `POST_NOTIFICATIONS` for good —
 * revoking it kills the instrumented process — so without the seam the "asks" half of this class
 * could never run twice on the same device. The one test that deliberately crosses into Android's
 * own dialog ([allow_handsOverToAndroidAndRecordsThatItDid]) grants the permission first, so the
 * system answers instantly instead of raising a window no Compose test can reach.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionPromptTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val buildInfo = BuildInfo(isDebug = true, sdkInt = 36)

    private val notDue = PermissionReadout(granted = true, shouldShowRationale = false)
    private val due = PermissionReadout(granted = false, shouldShowRationale = false)

    private fun prompt(settings: FakeSettingsRepository) = NotificationPrompt(
        settingsRepository = settings,
        time = TimeProvider { NOW },
        buildInfo = buildInfo,
        dispatchers = TestDispatcherProvider(),
    )

    private fun setContent(
        hasVideos: Boolean = true,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        checker: UpdateChecker = inertUpdateChecker(),
        buildInfo: BuildInfo = this.buildInfo,
        readout: PermissionReadout = due,
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = this.buildInfo) {
                NotificationPermissionPrompt(
                    hasVideos = hasVideos,
                    prompt = prompt(settings),
                    updateChecker = checker,
                    buildInfo = buildInfo,
                    readPermission = { readout },
                )
            }
        }
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    private val title get() = composeRule.onNodeWithText(label(R.string.notification_permission_title))

    // --- When it asks ---------------------------------------------------------------------------

    /**
     * The trigger is the library *becoming* non-empty, which is also the only order a cold start
     * ever produces: the row count arrives from Room after the first composition. Starting at
     * `false` and flipping is therefore both the negative control and the real sequence.
     */
    @Test
    fun theLibraryFillingUp_isWhatAsks() {
        lateinit var hasVideos: MutableState<Boolean>
        val settings = FakeSettingsRepository()
        composeRule.setContent {
            hasVideos = remember { mutableStateOf(false) }
            JellyshelfTheme(dynamicColor = false, buildInfo = buildInfo) {
                NotificationPermissionPrompt(
                    hasVideos = hasVideos.value,
                    prompt = prompt(settings),
                    updateChecker = inertUpdateChecker(),
                    buildInfo = buildInfo,
                    readPermission = { due },
                )
            }
        }
        title.assertDoesNotExist()

        composeRule.runOnIdle { hasVideos.value = true }

        title.assertIsDisplayed()
    }

    /** Below API 33 there is nothing to ask for, however inviting the rest of the state looks. */
    @Test
    fun belowApi33_asksForNothing() {
        setContent(buildInfo = BuildInfo(isDebug = true, sdkInt = 32))

        title.assertDoesNotExist()
    }

    /** Already granted: the question is answered, and re-asking it would be noise. */
    @Test
    fun aGrantedPermission_isNeverAskedFor() {
        setContent(readout = notDue)

        title.assertDoesNotExist()
    }

    /**
     * Denied twice, so Android will never show its dialog again — `shouldShowRationale` back to
     * `false` with `systemAsked` stored. The pass-through of *both* halves of the readout is what
     * this pins: get either wrong and the prompt returns weekly with an "Allow" that does nothing.
     */
    @Test
    fun aPermissionLockedByTwoDenials_isNeverAskedAgain() {
        setContent(
            settings = FakeSettingsRepository(
                notificationPromptAt = NOW - EIGHT_DAYS,
                notificationSystemAsked = true,
            ),
            readout = PermissionReadout(granted = false, shouldShowRationale = false),
        )

        title.assertDoesNotExist()
    }

    /** Two stacked dialogs are one dialog nobody reads; the rarer, expiring one wins. */
    @Test
    fun aPendingUpdateOffer_defersThePrompt() {
        val checker = checkerWithAnOffer()
        runBlocking { checker.checkNow() }

        setContent(checker = checker)

        title.assertDoesNotExist()
    }

    // --- What the answers write -------------------------------------------------------------

    @Test
    fun notNow_recordsADeclineAndCloses() {
        val settings = FakeSettingsRepository()
        setContent(settings = settings)

        composeRule.onNodeWithText(label(R.string.notification_permission_dismiss)).performClick()

        title.assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(NOW to false, settings.savedNotificationPrompt) }
    }

    /**
     * "Allow" reaches Android's own dialog and the *result callback* is what records it — so an
     * abandoned system dialog still counts as asked. Granting the permission first makes Android
     * answer synchronously, which keeps the test deterministic; the seam still reports it missing,
     * which is what put our dialog up. API 33+ only: below that, requesting this permission is
     * asking the platform about a string it has never heard of.
     */
    @Test
    fun allow_handsOverToAndroidAndRecordsThatItDid() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        grantNotificationPermission()
        val settings = FakeSettingsRepository()
        setContent(settings = settings)

        composeRule.onNodeWithText(label(R.string.notification_permission_allow)).performClick()

        title.assertDoesNotExist()
        composeRule.waitUntil(WAIT_MS) { settings.savedNotificationPrompt == NOW to true }
    }

    // --- An offer to defer to -----------------------------------------------------------------

    /**
     * A real [UpdateChecker] over stub sources, the same shape as
     * [UpdateOfferHostingTest][com.gmail.volkovskiyda.jellyshelf.ui.UpdateOfferHostingTest]: a
     * release-shaped build at version 100 offered version 200 from the GitHub channel.
     */
    private fun checkerWithAnOffer() = UpdateChecker(
        settingsRepository = FakeSettingsRepository(updateSource = UpdateSource.GITHUB),
        gitHubSource = object : GitHubReleaseSource(HttpClient(OkHttp), TestDispatcherProvider(), Json) {
            override suspend fun latestRelease() = UpdateInfo(
                versionCode = 200,
                versionName = "1.0.200",
                releaseNotes = "",
                downloadUrl = "https://example.invalid/jellyshelf-1.0.200.apk",
                source = UpdateSource.GITHUB,
            )
        },
        appDistributionSource = AppDistributionSource(InertTesterSignIn, FakeUpdateFlags()),
        buildInfo = BuildInfo(isDebug = false, sdkInt = 36, versionCode = 100),
        time = TimeProvider { NOW },
        dispatchers = TestDispatcherProvider(),
    )
}
