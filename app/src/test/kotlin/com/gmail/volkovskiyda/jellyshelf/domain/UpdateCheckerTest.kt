package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateCheckFailure
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

private val ONE_DAY = TimeUnit.DAYS.toMillis(1)

/** A plausible "now" well past every window, so `0` timestamps read as "never" rather than "just". */
private const val START = 1_800_000_000_000L

/**
 * [UpdateChecker]'s six gates and its three time windows, driven by a fake clock so nothing sleeps.
 *
 * The sources are subclassed rather than mocked: [AppDistributionSource] cannot be exercised on the
 * JVM at all (the Firebase singleton needs an Android runtime), and both classes are `open` for
 * exactly this, the way `DemoBackend` and `YtDlpMetadataSource` already are.
 */
class UpdateCheckerTest {

    private var clock = START

    private class FakeGitHub(
        private val answer: () -> UpdateInfo?,
    ) : GitHubReleaseSource(HttpClient(MockEngine { respondOk() }), TestDispatcherProvider(), Json) {
        var calls = 0
        override suspend fun latestRelease(): UpdateInfo? {
            calls++
            return answer()
        }
    }

    private class FakeAppDistribution(
        private val signedIn: Boolean = true,
        private val answer: () -> UpdateInfo? = { null },
    ) : AppDistributionSource() {
        var calls = 0
        var signInAttempts = 0
        override fun isTesterSignedIn() = signedIn
        override suspend fun signInTester() {
            signInAttempts++
        }

        override suspend fun latestRelease(): UpdateInfo? {
            calls++
            return answer()
        }
    }

    private fun update(versionCode: Int, source: UpdateSource = UpdateSource.GITHUB) = UpdateInfo(
        versionCode = versionCode,
        versionName = "1.0",
        releaseNotes = "",
        downloadUrl = "https://example.invalid/jellyshelf-1.0.$versionCode.apk",
        source = source,
    )

    private fun checker(
        settings: FakeSettingsRepository = FakeSettingsRepository(updateSource = UpdateSource.GITHUB),
        gitHub: GitHubReleaseSource = FakeGitHub { update(170) },
        appDistribution: AppDistributionSource = FakeAppDistribution(),
        isDebug: Boolean = false,
        versionCode: Int = 165,
    ) = UpdateChecker(
        settingsRepository = settings,
        gitHubSource = gitHub,
        appDistributionSource = appDistribution,
        buildInfo = BuildInfo(isDebug = isDebug, sdkInt = 36, versionCode = versionCode),
        // SAM-converted, so the tests below keep moving time by assigning to `clock`.
        time = TimeProvider { clock },
        dispatchers = TestDispatcherProvider(),
    )

    // --- Validity gates: this build cannot compare itself to anything ---

    /** A debug install is a different package at versionCode 1; every release would read as newer. */
    @Test
    fun aDebugBuild_asksNothing() = runTest {
        val gitHub = FakeGitHub { update(170) }
        checker(gitHub = gitHub, isDebug = true).checkNow()

        assertEquals(0, gitHub.calls)
    }

    /** A locally assembled release without -PbuildNumber reports 1, and would do the same. */
    @Test
    fun aBuildWithNoVersionCode_asksNothing() = runTest {
        val gitHub = FakeGitHub { update(170) }
        checker(gitHub = gitHub, versionCode = 1).checkNow()

        assertEquals(0, gitHub.calls)
    }

    /**
     * The default, and the only thing keeping Test Lab, the live journey and the profiling
     * variants off the network — none of which is a debug build.
     */
    @Test
    fun noChannelSelected_asksNothing() = runTest {
        val gitHub = FakeGitHub { update(170) }
        checker(settings = FakeSettingsRepository(), gitHub = gitHub).checkNow()

        assertEquals(0, gitHub.calls)
    }

    // --- The check interval: how often we ask ---

    @Test
    fun insideTheCheckWindow_asksNothing() = runTest {
        val settings = FakeSettingsRepository(
            updateSource = UpdateSource.GITHUB,
            lastUpdateCheckAt = START - TimeUnit.HOURS.toMillis(2),
        )
        val gitHub = FakeGitHub { update(170) }
        checker(settings = settings, gitHub = gitHub).checkOnStart()

        assertEquals(0, gitHub.calls)
    }

    @Test
    fun aDayAfterTheLastCheck_asksAgain() = runTest {
        val settings = FakeSettingsRepository(
            updateSource = UpdateSource.GITHUB,
            lastUpdateCheckAt = START - ONE_DAY,
        )
        val gitHub = FakeGitHub { update(170) }
        checker(settings = settings, gitHub = gitHub).checkOnStart()

        assertEquals(1, gitHub.calls)
    }

    /** The interval is about how often we ask, so "asked, found nothing" still counts as asking. */
    @Test
    fun findingNothing_stillStampsTheCheck() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        checker(settings = settings, gitHub = FakeGitHub { null }).checkOnStart()

        assertEquals(START, settings.savedLastUpdateCheckAt)
    }

    /** A transient outage must not buy a day of silence — nothing was actually asked. */
    @Test
    fun aFailedCheck_doesNotStampTheCheck() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(
            settings = settings,
            gitHub = FakeGitHub { throw IOException("no route to host") },
        )
        checker.checkOnStart()

        assertEquals(0L, settings.savedLastUpdateCheckAt)
        assertEquals(UpdateCheckError.Network, checker.error.value)
        assertFalse(checker.checking.value)
    }

    // --- Offer rule 1: genuinely newer ---

    @Test
    fun theSameVersion_isNotOffered() = runTest {
        val checker = checker(gitHub = FakeGitHub { update(165) })
        checker.checkOnStart()

        assertNull(checker.available.value)
    }

    @Test
    fun aNewerVersion_isOffered() = runTest {
        val checker = checker(gitHub = FakeGitHub { update(170) })
        checker.checkOnStart()

        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    // --- Offer rule 2: the one-dialog-a-day floor ---

    /** Three releases in one afternoon must produce one dialog, not three. */
    @Test
    fun aDialogTwoHoursAgo_suppressesEvenABrandNewVersion() = runTest {
        val settings = FakeSettingsRepository(
            updateSource = UpdateSource.GITHUB,
            lastUpdateDialogAt = START - TimeUnit.HOURS.toMillis(2),
        )
        val checker = checker(settings = settings, gitHub = FakeGitHub { update(170) })
        checker.checkOnStart()

        assertNull(checker.available.value)
    }

    @Test
    fun aDialogTwentyFiveHoursAgo_doesNotSuppress() = runTest {
        val settings = FakeSettingsRepository(
            updateSource = UpdateSource.GITHUB,
            lastUpdateDialogAt = START - TimeUnit.HOURS.toMillis(25),
        )
        val checker = checker(settings = settings, gitHub = FakeGitHub { update(170) })
        checker.checkOnStart()

        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    // --- Offer rule 3: the snooze ---

    @Test
    fun aDismissedVersion_staysQuietTheNextDay() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        checker(settings = settings, gitHub = FakeGitHub { update(170) }).dismiss(update(170))

        clock = START + ONE_DAY
        val checker = checker(settings = settings, gitHub = FakeGitHub { update(170) })
        checker.checkOnStart()

        assertNull(checker.available.value)
    }

    /** A dismissal expires. This is the deliberate reversal of "dismissing mutes it forever". */
    @Test
    fun aDismissedVersion_comesBackOnDayEight() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        checker(settings = settings, gitHub = FakeGitHub { update(170) }).dismiss(update(170))

        clock = START + TimeUnit.DAYS.toMillis(8)
        val checker = checker(settings = settings, gitHub = FakeGitHub { update(170) })
        checker.checkOnStart()

        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    /**
     * The scenario the whole re-show model exists for: install 1.0, dismiss the 1.1 prompt, and
     * 1.2 ships. It must surface promptly rather than waiting out 1.1's seven days.
     */
    @Test
    fun aNewerBuildThanTheDismissedOne_isOfferedWithoutWaiting() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        checker(settings = settings, gitHub = FakeGitHub { update(170) }).dismiss(update(170))

        clock = START + ONE_DAY
        val checker = checker(settings = settings, gitHub = FakeGitHub { update(181) })
        checker.checkOnStart()

        assertEquals(181, checker.available.value?.info?.versionCode)
    }

    /**
     * A tag cut from an older commit than the latest tester build produces a *lower* code. "Not
     * newer, so wait the snooze out" is the right answer for that as much as for an equal one.
     */
    @Test
    fun aVersionOlderThanTheDismissedOne_waitsOutTheSnooze() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        checker(settings = settings, gitHub = FakeGitHub { update(181) }).dismiss(update(181))

        clock = START + ONE_DAY
        val checker = checker(settings = settings, gitHub = FakeGitHub { update(170) })
        checker.checkOnStart()

        assertNull(checker.available.value)
    }

    /** Dismissing GitHub's offer must not silence App Distribution's. */
    @Test
    fun aDismissalOnOneChannel_doesNotSilenceTheOther() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.APP_DISTRIBUTION)
        checker(settings = settings).dismiss(update(170, UpdateSource.GITHUB))

        val checker = checker(
            settings = settings,
            appDistribution = FakeAppDistribution { update(170, UpdateSource.APP_DISTRIBUTION) },
        )
        checker.checkOnStart()

        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    // --- "Check now" skips politeness, not validity ---

    @Test
    fun checkNow_ignoresEveryWindow() = runTest {
        val settings = FakeSettingsRepository(
            updateSource = UpdateSource.GITHUB,
            lastUpdateCheckAt = START,
            lastUpdateDialogAt = START,
        )
        settings.setDismissedUpdate(UpdateSource.GITHUB, versionCode = 170, timestamp = START)

        val gitHub = FakeGitHub { update(170) }
        val checker = checker(settings = settings, gitHub = gitHub)
        checker.checkNow()

        assertEquals(1, gitHub.calls)
        assertEquals(170, checker.available.value?.info?.versionCode)
        // Something was found, so this is an offer rather than an "up to date".
        assertFalse(checker.upToDate.value)
    }

    /**
     * The politeness windows are the user's to skip; "is it actually newer" is not.
     *
     * This is the GitHub channel's shape specifically: `releases/latest` answers with the newest
     * release whether or not it is an upgrade, so without this rule a user already running the
     * newest build taps "Check now" and is offered the build they are running. App Distribution
     * hides the same case behind a null result, which is why it never showed up there.
     */
    @Test
    fun checkNow_doesNotOfferTheBuildAlreadyInstalled() = runTest {
        val checker = checker(gitHub = FakeGitHub { update(165) }, versionCode = 165)
        checker.checkNow()

        assertNull(checker.available.value)
        assertTrue(checker.upToDate.value)
    }

    /** A tag cut from an older commit than the installed build is not an update either. */
    @Test
    fun checkNow_doesNotOfferAnOlderBuild() = runTest {
        val checker = checker(gitHub = FakeGitHub { update(160) }, versionCode = 165)
        checker.checkNow()

        assertNull(checker.available.value)
        assertTrue(checker.upToDate.value)
    }

    /** A channel that answers "nothing new" at all — App Distribution's null — reads the same. */
    @Test
    fun checkNow_reportsUpToDateWhenTheChannelHasNothing() = runTest {
        val checker = checker(gitHub = FakeGitHub { null })
        checker.checkNow()

        assertNull(checker.available.value)
        assertTrue(checker.upToDate.value)
    }

    /**
     * Nobody asked, so nobody is told. A cold-start check finding nothing is the ordinary case;
     * announcing it would put a line on the settings screen the user never requested.
     */
    @Test
    fun theAutomaticCheck_neverReportsUpToDate() = runTest {
        val checker = checker(gitHub = FakeGitHub { null })
        checker.checkOnStart()

        assertFalse(checker.upToDate.value)
    }

    /** It describes the *latest* answer, so the next check must not leave the old one on screen. */
    @Test
    fun upToDate_isClearedWhenTheNextCheckStarts() = runTest {
        var answer: UpdateInfo? = null
        val checker = checker(gitHub = FakeGitHub { answer }, versionCode = 165)
        checker.checkNow()
        assertTrue(checker.upToDate.value)

        answer = update(170)
        checker.checkNow()

        assertFalse(checker.upToDate.value)
        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    /** A failed check has no answer at all, and must not claim the reassuring one. */
    @Test
    fun aFailedCheck_isNotUpToDate() = runTest {
        val checker = checker(gitHub = FakeGitHub { throw IOException("no network") })
        checker.checkNow()

        assertFalse(checker.upToDate.value)
        assertEquals(UpdateCheckError.Network, checker.error.value)
    }

    /** Switching channels invalidates the previous channel's answer, which was about a different feed. */
    @Test
    fun selectingAChannel_clearsAnEarlierUpToDate() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(settings = settings, gitHub = FakeGitHub { null })
        checker.checkNow()
        assertTrue(checker.upToDate.value)

        checker.selectSource(UpdateSource.NONE)

        assertFalse(checker.upToDate.value)
    }

    // --- Tester sign-in ---

    /**
     * The cold-start path may never open a Custom Tab — that would throw a browser over the app on
     * launch. It reports why instead, and Settings points the user at "Check now".
     */
    @Test
    fun aSignedOutTester_isNeverSignedInByTheAutomaticCheck() = runTest {
        val appDistribution = FakeAppDistribution(signedIn = false) { update(170) }
        val checker = checker(
            settings = FakeSettingsRepository(updateSource = UpdateSource.APP_DISTRIBUTION),
            appDistribution = appDistribution,
        )
        checker.checkOnStart()

        assertEquals(0, appDistribution.signInAttempts)
        assertEquals(0, appDistribution.calls)
        assertEquals(UpdateCheckError.SignInRequired, checker.error.value)
        assertNull(checker.available.value)
    }

    /** "Check now" is the one path allowed to ask, because the user just asked. */
    @Test
    fun checkNow_maySignATesterIn() = runTest {
        val appDistribution = FakeAppDistribution(signedIn = false) {
            update(170, UpdateSource.APP_DISTRIBUTION)
        }
        val checker = checker(
            settings = FakeSettingsRepository(updateSource = UpdateSource.APP_DISTRIBUTION),
            appDistribution = appDistribution,
        )
        checker.checkNow()

        assertEquals(1, appDistribution.signInAttempts)
        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    // --- Selecting a channel (the sign-in gate) ---

    /** Off and GitHub need nothing from Firebase, so neither may ask for a sign-in. */
    @Test
    fun selectingAChannelThatNeedsNoTester_justWritesIt() = runTest {
        val settings = FakeSettingsRepository()
        val appDistribution = FakeAppDistribution(signedIn = false)
        checker(settings = settings, appDistribution = appDistribution)
            .selectSource(UpdateSource.GITHUB)

        assertEquals(UpdateSource.GITHUB, settings.updateSource.first())
        assertEquals(0, appDistribution.signInAttempts)
    }

    @Test
    fun selectingAppDistributionWhenAlreadySignedIn_doesNotAskAgain() = runTest {
        val settings = FakeSettingsRepository()
        val appDistribution = FakeAppDistribution(signedIn = true)
        checker(settings = settings, appDistribution = appDistribution)
            .selectSource(UpdateSource.APP_DISTRIBUTION)

        assertEquals(UpdateSource.APP_DISTRIBUTION, settings.updateSource.first())
        assertEquals(0, appDistribution.signInAttempts)
    }

    @Test
    fun selectingAppDistributionWhenSignedOut_signsInFirst() = runTest {
        val settings = FakeSettingsRepository()
        val appDistribution = FakeAppDistribution(signedIn = false)
        val checker = checker(settings = settings, appDistribution = appDistribution)
        checker.selectSource(UpdateSource.APP_DISTRIBUTION)

        assertEquals(1, appDistribution.signInAttempts)
        assertEquals(UpdateSource.APP_DISTRIBUTION, settings.updateSource.first())
        assertFalse(checker.signingIn.value)
    }

    /**
     * The write must survive the screen that asked for it.
     *
     * `signInTester()` opens a Custom Tab, and returning through `SignInResultActivity` recreates
     * the activity — which clears the settings screen's `ViewModelStore`. While this ran on
     * `viewModelScope` the coroutine was cancelled between a *successful* sign-in and the write, so
     * the channel silently stayed Off; that reproduced on-device twice on 2026-08-06.
     *
     * Cancelling the caller's scope here stands in for the ViewModel being cleared: the write still
     * has to land, which it only does because [UpdateChecker.selectSource] owns its own scope.
     */
    @Test
    fun selectingAChannel_survivesTheCallingScopeBeingCancelled() = runTest {
        val settings = FakeSettingsRepository()
        // Suspends the way a Custom Tab does: the user is off in the browser, and nothing has
        // been written yet.
        val inCustomTab = CompletableDeferred<Unit>()
        val appDistribution = object : AppDistributionSource() {
            override fun isTesterSignedIn() = false
            override suspend fun signInTester() = inCustomTab.await()
        }
        val checker = checker(settings = settings, appDistribution = appDistribution)
        val screen = CoroutineScope(Job() + Dispatchers.Unconfined)

        screen.launch { checker.selectSource(UpdateSource.APP_DISTRIBUTION) }
        // Coming back through SignInResultActivity recreates the activity, clearing the ViewModel.
        screen.cancel()
        // ...and only then does the sign-in report success.
        inCustomTab.complete(Unit)

        assertEquals(UpdateSource.APP_DISTRIBUTION, settings.updateSource.first())
    }

    /**
     * The point of the gate: a channel that provably cannot answer is never persisted, so the
     * selection snaps back rather than leaving the user watching a dead one forever.
     */
    @Test
    fun aCancelledSignIn_leavesTheChannelUnchangedAndSaysWhy() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val appDistribution = object : AppDistributionSource() {
            override fun isTesterSignedIn() = false
            override suspend fun signInTester() =
                throw UpdateCheckFailure(UpdateCheckError.SignInCancelled, "cancelled", null)
        }
        val checker = checker(settings = settings, appDistribution = appDistribution)
        checker.selectSource(UpdateSource.APP_DISTRIBUTION)

        assertEquals(UpdateSource.GITHUB, settings.updateSource.first())
        assertEquals(UpdateCheckError.SignInCancelled, checker.error.value)
        assertFalse(checker.signingIn.value)
    }

    // --- Bookkeeping ---

    /** Both halves of the dismissal, in one write — a code without a time reads as expired. */
    @Test
    fun dismissing_recordsTheCodeAndTheTimeAndClearsTheOffer() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(settings = settings)
        checker.checkOnStart()
        assertTrue(checker.available.value != null)

        checker.dismiss(update(170))

        assertNull(checker.available.value)
        assertEquals(170, settings.dismissedUpdate(UpdateSource.GITHUB).first())
        assertEquals(START, settings.dismissedUpdateAt(UpdateSource.GITHUB).first())
    }

    /**
     * The floor is stamped when the dialog is *seen*, not when the check finds something: a user
     * who taps "Check now" and never returns to Library was not interrupted.
     */
    @Test
    fun findingAnUpdate_doesNotBurnTheDialogFloor() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(settings = settings)
        checker.checkOnStart()

        assertEquals(0L, settings.savedLastUpdateDialogAt)

        checker.markDialogShown(checker.available.value!!)

        assertEquals(START, settings.savedLastUpdateDialogAt)
    }

    // --- Offers the user asked for ---

    /** Provenance travels with the offer, so the host can tell the two apart. */
    @Test
    fun anAutomaticOffer_isNotMarkedRequested() = runTest {
        val checker = checker()
        checker.checkOnStart()

        assertFalse(checker.available.value!!.requested)
    }

    @Test
    fun aManualOffer_isMarkedRequested() = runTest {
        val checker = checker()
        checker.checkNow()

        assertTrue(checker.available.value!!.requested)
    }

    /**
     * The floor bounds how often the app *interrupts*. A dialog the user summoned from Settings
     * interrupts nobody, and spending the floor on it would mute the next automatic offer for a
     * day — quite possibly of a build newer than the one just shown.
     */
    @Test
    fun aRequestedDialog_doesNotBurnTheFloor() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(settings = settings)
        checker.checkNow()

        checker.markDialogShown(checker.available.value!!)

        assertEquals(0L, settings.savedLastUpdateDialogAt)
    }

    /**
     * And the floor still stops an *automatic* offer that arrives right after a requested one —
     * the exemption is per dialog, not a switch that turns the floor off.
     */
    @Test
    fun aManualCheck_leavesTheFloorAvailableForTheNextAutomaticOffer() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(settings = settings)
        checker.checkNow()
        checker.markDialogShown(checker.available.value!!)

        // A day later, so the check interval has passed and only the dialog floor could suppress.
        clock = START + ONE_DAY
        checker.checkOnStart()

        assertEquals(170, checker.available.value?.info?.versionCode)
    }

    /** Choosing to update is not a dismissal: a failed install must re-prompt, not snooze a week. */
    @Test
    fun clearingTheOffer_recordsNoDismissal() = runTest {
        val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)
        val checker = checker(settings = settings)
        checker.checkOnStart()

        checker.clearAvailable()

        assertNull(checker.available.value)
        assertEquals(0, settings.dismissedUpdate(UpdateSource.GITHUB).first())
    }
}
