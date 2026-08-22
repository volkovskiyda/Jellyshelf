package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

private val ONE_DAY = TimeUnit.DAYS.toMillis(1)

/** A plausible "now" well past the window, so a `0` timestamp reads as "never", not "just now". */
private const val START = 1_800_000_000_000L

/**
 * When [LocalNetworkPrompt] may interrupt, driven by a fake clock so nothing sleeps.
 *
 * The same shape as [NotificationPromptTest], because the policy is deliberately the same one: the
 * two Android facts it cannot read for itself arrive as parameters, and their combinations are the
 * point — `shouldShowRationale` says `false` both before the first ask and after the second denial
 * has locked the permission for good.
 *
 * What is *not* shared with the notification prompt is who gets asked. There is no gate here for
 * having signed in: the permission can go missing long after a session exists, and this dialog is
 * the app's only way to request it, so a signed-in install that cannot reach its server has to be
 * askable. [rearmedByAFailure_isDueImmediately] is the other half of that.
 */
class LocalNetworkPromptTest {

    private var clock = START

    private fun prompt(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        sdkInt: Int = LOCAL_NETWORK_PERMISSION_API,
    ) = LocalNetworkPrompt(
        settingsRepository = settings,
        // SAM-converted, so the tests below keep moving time by assigning to `clock`.
        time = TimeProvider { clock },
        buildInfo = BuildInfo(isDebug = false, sdkInt = sdkInt),
        dispatchers = TestDispatcherProvider(),
    )

    private fun state(answeredAt: Long = 0L, systemAsked: Boolean = false) =
        LocalNetworkPromptState(answeredAt, systemAsked)

    // --- The gates ----------------------------------------------------------------------------

    /** The case it exists for: the permission is missing and has never been asked about. */
    @Test
    fun neverAsked_isDue() {
        assertTrue(prompt().due(state(), granted = false, shouldShowRationale = false))
    }

    /** Nothing to ask for: the permission is already held. */
    @Test
    fun alreadyGranted_isNotDue() {
        assertFalse(prompt().due(state(), granted = true, shouldShowRationale = false))
    }

    /**
     * Below the enforcement level local addresses are not blocked, whatever the store remembers —
     * and on API 36 in particular the permission exists but cannot be requested at all.
     */
    @Test
    fun belowTheEnforcementLevel_isNeverDue() {
        assertFalse(
            prompt(sdkInt = LOCAL_NETWORK_PERMISSION_API - 1)
                .due(state(), granted = false, shouldShowRationale = false),
        )
    }

    // --- The snooze ---------------------------------------------------------------------------

    @Test
    fun justDeclined_isNotDue() {
        assertFalse(
            prompt().due(state(answeredAt = clock), granted = false, shouldShowRationale = false),
        )
    }

    @Test
    fun sixDaysAfterDeclining_isNotDue() {
        val declinedAt = clock
        clock += 6 * ONE_DAY

        assertFalse(
            prompt().due(state(answeredAt = declinedAt), granted = false, shouldShowRationale = false),
        )
    }

    @Test
    fun sevenDaysAfterDeclining_isDueAgain() {
        val declinedAt = clock
        clock += 7 * ONE_DAY

        assertTrue(
            prompt().due(state(answeredAt = declinedAt), granted = false, shouldShowRationale = false),
        )
    }

    // --- Telling "never asked" from "locked for good" -----------------------------------------

    /**
     * One denial: Android still shows its dialog, and says so through `shouldShowRationale`. The
     * week is what bounds the nagging, not the platform.
     */
    @Test
    fun deniedOnce_isDueAgainAfterTheWeek() {
        val deniedAt = clock
        clock += 7 * ONE_DAY

        assertTrue(
            prompt().due(
                state(answeredAt = deniedAt, systemAsked = true),
                granted = false,
                shouldShowRationale = true,
            ),
        )
    }

    /**
     * Denied twice: the permission is locked, the system dialog will never appear again, and an
     * "Allow" button that does nothing is worse than no prompt at all — the server stays
     * unreachable either way, but a weekly dialog would promise a fix it cannot deliver.
     */
    @Test
    fun deniedForGood_isNeverDueAgain() {
        val deniedAt = clock
        clock += 365 * ONE_DAY

        assertFalse(
            prompt().due(
                state(answeredAt = deniedAt, systemAsked = true),
                granted = false,
                shouldShowRationale = false,
            ),
        )
    }

    /**
     * The same `shouldShowRationale = false` as above, but our dialog has never handed over to
     * Android — so this is a first ask, not a locked one.
     */
    @Test
    fun declinedOurDialogOnly_isStillDueAfterTheWeek() {
        val declinedAt = clock
        clock += 7 * ONE_DAY

        assertTrue(
            prompt().due(
                state(answeredAt = declinedAt, systemAsked = false),
                granted = false,
                shouldShowRationale = false,
            ),
        )
    }

    // --- Recording and re-arming --------------------------------------------------------------

    @Test
    fun recordingAnAnswer_stampsTheClockAndTheFlag() = runTest {
        val settings = FakeSettingsRepository()
        prompt(settings).record(systemAsked = true).join()

        assertEquals(clock to true, settings.savedLocalNetworkPrompt)
    }

    /** "Not now" restarts the week without claiming Android has been asked. */
    @Test
    fun decliningOurDialog_doesNotClaimTheSystemWasAsked() = runTest {
        val settings = FakeSettingsRepository()
        prompt(settings).record(systemAsked = false).join()

        assertEquals(clock to false, settings.savedLocalNetworkPrompt)
    }

    /**
     * A later "Not now" cannot un-ask the system dialog — otherwise a locked permission would look
     * like a fresh install again, and the prompt would return every week for good.
     */
    @Test
    fun aLaterDecline_doesNotClearTheSystemAskedFlag() = runTest {
        val settings = FakeSettingsRepository()
        val prompt = prompt(settings)
        prompt.record(systemAsked = true).join()

        clock += 7 * ONE_DAY
        prompt.record(systemAsked = false).join()

        assertEquals(clock to true, settings.savedLocalNetworkPrompt)
        assertTrue(settings.localNetworkSystemAsked.first())
    }

    /**
     * A connection that failed the way a missing grant fails is worth more than the week: the user
     * is looking at "server unreachable" right now, which is the moment the explanation lands.
     */
    @Test
    fun rearmedByAFailure_isDueImmediately() = runTest {
        val settings = FakeSettingsRepository()
        val prompt = prompt(settings)
        prompt.record(systemAsked = false).join()

        prompt.rearm().join()

        assertTrue(
            prompt.due(
                state(answeredAt = settings.savedLocalNetworkPrompt.first),
                granted = false,
                shouldShowRationale = false,
            ),
        )
    }

    /**
     * Re-arming clears the snooze, never the record of Android having had its turn — otherwise
     * every failed connection would resurrect a permission the user has locked, and the dialog
     * would be back weekly with an "Allow" that does nothing.
     */
    @Test
    fun rearming_doesNotUnlockAPermissionAndroidHasLocked() = runTest {
        val settings = FakeSettingsRepository()
        val prompt = prompt(settings)
        prompt.record(systemAsked = true).join()

        prompt.rearm().join()

        assertEquals(0L to true, settings.savedLocalNetworkPrompt)
        assertFalse(
            prompt.due(
                state(answeredAt = 0L, systemAsked = true),
                granted = false,
                shouldShowRationale = false,
            ),
        )
    }
}
