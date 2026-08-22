package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.InstallOutcome
import com.gmail.volkovskiyda.jellyshelf.domain.LOCAL_NETWORK_PERMISSION_API
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeUpdateFlags
import com.gmail.volkovskiyda.jellyshelf.ui.InertApkInstall
import com.gmail.volkovskiyda.jellyshelf.ui.InertTesterSignIn
import com.gmail.volkovskiyda.jellyshelf.ui.PermissionReadout
import com.gmail.volkovskiyda.jellyshelf.ui.RecordingUpdateCheckSchedule
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.ui.inertUpdateChecker
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** A plausible instant, for the same reason as everywhere else: `0` must read as "never". */
private const val NOW = 1_800_000_000_000L

/** Past the week-long snooze, so a stored answer is stale rather than fresh. */
private val EIGHT_DAYS = TimeUnit.DAYS.toMillis(8)

/** Long enough for a permission result to be posted back and written, short enough to fail fast. */
private const val WAIT_MS = 5_000L

/**
 * What the settings-screen host does with [LocalNetworkPrompt]'s answers — the glue that
 * [LocalNetworkPromptTest][com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPromptTest] (the
 * policy) and `LocalNetworkPermissionDialog` (the dialog, a near-copy of the notification
 * rationale pinned by `NotificationPermissionDialogTest`) leave uncovered between them: when the
 * dialog is hosted at all, and what each button writes back.
 *
 * The platform's two answers arrive through the `readPermission` seam because they cannot be
 * staged on a device — `pm revoke` kills the instrumented process along with the app — and the API
 * level arrives through `permissionExists` because the suite runs on API 31 as well as on hardware
 * new enough to have the permission at all. With both passed explicitly the composable touches no
 * Koin container, which is what lets this run against a plain [ComponentActivity].
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionPromptTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val missing = PermissionReadout(granted = false, shouldShowRationale = false)
    private val held = PermissionReadout(granted = true, shouldShowRationale = false)

    private fun prompt(settings: SettingsRepository) = LocalNetworkPrompt(
        settingsRepository = settings,
        time = TimeProvider { NOW },
        buildInfo = BuildInfo(isDebug = true, sdkInt = LOCAL_NETWORK_PERMISSION_API),
        dispatchers = TestDispatcherProvider(),
    )

    private fun setContent(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        permissionExists: Boolean = true,
        readout: PermissionReadout = missing,
        permission: String = Manifest.permission.ACCESS_LOCAL_NETWORK,
        checker: UpdateChecker = inertUpdateChecker(),
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                LocalNetworkPermissionPrompt(
                    prompt = prompt(settings),
                    permission = permission,
                    permissionExists = permissionExists,
                    updateChecker = checker,
                    readPermission = { readout },
                )
            }
        }
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    private val title get() = composeRule.onNodeWithText(label(R.string.local_network_permission_title))

    // --- When it asks ---------------------------------------------------------------------------

    /** The one case it exists for: a device that has the permission and has not granted it. */
    @Test
    fun aMissingPermission_isExplainedBeforeItIsRequested() {
        setContent()

        title.assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.local_network_permission_body)).assertIsDisplayed()
    }

    /** Below API 37 nothing local is blocked, so there is nothing to ask for. */
    @Test
    fun aPlatformWithoutThePermission_asksForNothing() {
        setContent(permissionExists = false)

        title.assertDoesNotExist()
    }

    /** Already granted: the question is answered, and re-asking it would be noise. */
    @Test
    fun aGrantedPermission_isNeverAskedFor() {
        setContent(readout = held)

        title.assertDoesNotExist()
    }

    /** A decline holds for the week — the whole point of persisting the answer. */
    @Test
    fun anAnswerFromYesterday_isStillSnoozed() {
        val yesterday = NOW - TimeUnit.DAYS.toMillis(1)
        setContent(settings = FakeSettingsRepository(localNetworkPromptAt = yesterday))

        title.assertDoesNotExist()
    }

    /** And expires: a week later the question is worth putting again. */
    @Test
    fun anAnswerFromLastWeek_isAskedAgain() {
        setContent(settings = FakeSettingsRepository(localNetworkPromptAt = NOW - EIGHT_DAYS))

        title.assertIsDisplayed()
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
                localNetworkPromptAt = NOW - EIGHT_DAYS,
                localNetworkSystemAsked = true,
            ),
            readout = PermissionReadout(granted = false, shouldShowRationale = false),
        )

        title.assertDoesNotExist()
    }

    /**
     * The regression this prompt was rebuilt for. Its inputs used to include the settings screen's
     * `signedIn` flag, which starts at the ViewModel's default `false` and flips when the persisted
     * snapshot lands a frame or more later — so on a signed-in app the dialog opened over the
     * *library*, during the navigation transition, and closed itself before it could be answered.
     *
     * The rule that replaces it: nothing may be decided from a store that has not answered yet.
     * [SilentUntilPushed] holds the answer back the way a cold read does — no emission at all,
     * rather than a plausible default — and the dialog appears only once it arrives.
     */
    @Test
    fun aStoreThatHasNotAnsweredYet_asksForNothing() {
        val store = SilentUntilPushed()
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                LocalNetworkPermissionPrompt(
                    prompt = prompt(store),
                    permissionExists = true,
                    updateChecker = inertUpdateChecker(),
                    readPermission = { missing },
                )
            }
        }
        title.assertDoesNotExist()

        store.push(answeredAt = 0L, systemAsked = false)

        title.assertIsDisplayed()
    }

    // --- What the answers write -------------------------------------------------------------

    /**
     * "Not now" closes it and starts the week. Persisted, unlike the in-composition flag this used
     * to keep: the ViewModel behind this screen is recreated on every tab switch, so an unpersisted
     * decline lasted exactly until the user looked at the library and came back.
     */
    @Test
    fun notNow_recordsADeclineAndCloses() {
        val settings = FakeSettingsRepository()
        setContent(settings = settings)

        composeRule.onNodeWithText(label(R.string.local_network_permission_dismiss)).performClick()

        title.assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(NOW to false, settings.savedLocalNetworkPrompt) }
    }

    /**
     * "Allow" closes ours and hands over to Android's — and the *result callback* is what records
     * the hand-over, so an abandoned system dialog still counts as having reached the platform.
     *
     * The permission requested is `INTERNET`, and the substitution is the point. Unlike
     * `POST_NOTIFICATIONS`, `ACCESS_LOCAL_NETWORK` cannot be pre-granted from a test on the one
     * kind of device that has it, so requesting it for real raises a system window on top of the
     * app — which this test cannot see and would not fail on, since `assertDoesNotExist` only
     * inspects our own composition. It would pass with Android's dialog left standing at teardown,
     * and take the next test in the run down with it. A permission the app already holds makes the
     * contract answer synchronously instead, on every API level this suite runs on, through the
     * same launcher the real string goes through — which is the part worth pinning.
     */
    @Test
    fun allow_handsOverToAndroidAndRecordsThatItDid() {
        val settings = FakeSettingsRepository()
        setContent(settings = settings, permission = Manifest.permission.INTERNET)

        composeRule.onNodeWithText(label(R.string.local_network_permission_allow)).performClick()

        title.assertDoesNotExist()
        composeRule.waitUntil(WAIT_MS) { settings.savedLocalNetworkPrompt == NOW to true }
    }

    // --- An offer to defer to -----------------------------------------------------------------

    /**
     * "Check for updates" is answered on this tab, so its dialog is already on screen — and two
     * stacked dialogs are one dialog nobody reads. [UpdateChecker.checkNow] is the manual check,
     * which is what marks the offer as requested.
     */
    @Test
    fun anOfferTheUserAskedFor_defersThePrompt() {
        val checker = checkerWithAnOffer()
        runBlocking { checker.checkNow() }
        assertEquals(true, checker.available.value?.requested)

        setContent(checker = checker)

        title.assertDoesNotExist()
    }

    /**
     * A background offer is *not* shown on this tab — `MainActivity` holds it back for the library
     * — so there is nothing to stack under and nothing to wait for. Waiting anyway would strand a
     * user whose LAN is blocked behind an offer they may not see for days.
     */
    @Test
    fun anOfferNobodyAskedFor_doesNotDeferThePrompt() {
        val checker = checkerWithAnOffer()
        runBlocking { checker.checkPeriodic() }
        // Asserted, or a check that quietly found nothing would make this pass for no reason.
        assertEquals(false, checker.available.value?.requested)

        setContent(checker = checker)

        title.assertIsDisplayed()
    }

    /**
     * A real [UpdateChecker] over stub sources, the same shape as `NotificationPermissionPromptTest`'s:
     * a release-shaped build at version 100 offered version 200 from the GitHub channel.
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
        buildInfo = BuildInfo(isDebug = false, sdkInt = LOCAL_NETWORK_PERMISSION_API, versionCode = 100),
        time = TimeProvider { NOW },
        dispatchers = TestDispatcherProvider(),
        updateCheckSchedule = RecordingUpdateCheckSchedule(),
        apkInstaller = InertApkInstall,
        installOutcome = InstallOutcome(),
    )
}

/**
 * A settings store whose two prompt keys answer nothing until [push] — the shape of a real cold
 * read, where DataStore's first emission arrives some frames after the screen is on display.
 *
 * A shared flow with no replay rather than a state flow with a default: a default *is* the bug
 * this pins. Everything else is delegated, since the prompt reads nothing else.
 */
private class SilentUntilPushed(
    private val delegate: FakeSettingsRepository = FakeSettingsRepository(),
) : SettingsRepository by delegate {

    private val at = MutableSharedFlow<Long>(replay = 1)
    private val asked = MutableSharedFlow<Boolean>(replay = 1)

    override val localNetworkPromptAt: Flow<Long> = at.asSharedFlow()
    override val localNetworkSystemAsked: Flow<Boolean> = asked.asSharedFlow()

    /** The store answering at last. Replayed, so a late collector still sees it. */
    fun push(answeredAt: Long, systemAsked: Boolean) {
        check(at.tryEmit(answeredAt) && asked.tryEmit(systemAsked)) { "buffered emit failed" }
    }
}
