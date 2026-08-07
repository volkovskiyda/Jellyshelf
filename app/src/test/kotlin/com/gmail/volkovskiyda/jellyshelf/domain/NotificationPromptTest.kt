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
 * When [NotificationPrompt] may interrupt, driven by a fake clock so nothing sleeps.
 *
 * The two Android facts it cannot read for itself — whether the permission is held, and what
 * `shouldShowRequestPermissionRationale` says — arrive as parameters, which is what keeps this a
 * JVM test. Their combinations are the whole point: the same `false` from `shouldShowRationale`
 * means "never asked" before the first system dialog and "locked for good" after the second
 * denial, and getting that pair wrong is the difference between one prompt and a weekly one that
 * provably cannot work.
 */
class NotificationPromptTest {

    private var clock = START

    private fun prompt(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        sdkInt: Int = 36,
    ) = NotificationPrompt(
        settingsRepository = settings,
        // SAM-converted, so the tests below keep moving time by assigning to `clock`.
        time = TimeProvider { clock },
        buildInfo = BuildInfo(isDebug = false, sdkInt = sdkInt),
        dispatchers = TestDispatcherProvider(),
    )

    private fun state(answeredAt: Long = 0L, systemAsked: Boolean = false) =
        NotificationPromptState(answeredAt, systemAsked)

    // --- The gates ----------------------------------------------------------------------------

    @Test
    fun neverAsked_andTheLibraryHasVideos_isDue() {
        assertTrue(prompt().due(state(), granted = false, shouldShowRationale = false))
    }

    /** Nothing to ask for: the permission is already held. */
    @Test
    fun alreadyGranted_isNotDue() {
        assertFalse(prompt().due(state(), granted = true, shouldShowRationale = false))
    }

    /** Below API 33 the permission does not exist, whatever the store remembers. */
    @Test
    fun belowApi33_isNeverDue() {
        assertFalse(prompt(sdkInt = 32).due(state(), granted = false, shouldShowRationale = false))
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
     * "Allow" button that does nothing is worse than no prompt at all.
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

    // --- Recording ----------------------------------------------------------------------------

    @Test
    fun recordingAnAnswer_stampsTheClockAndTheFlag() = runTest {
        val settings = FakeSettingsRepository()
        prompt(settings).record(systemAsked = true).join()

        assertEquals(clock to true, settings.savedNotificationPrompt)
    }

    /** "Not now" restarts the week without claiming Android has been asked. */
    @Test
    fun decliningOurDialog_doesNotClaimTheSystemWasAsked() = runTest {
        val settings = FakeSettingsRepository()
        prompt(settings).record(systemAsked = false).join()

        assertEquals(clock to false, settings.savedNotificationPrompt)
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

        assertEquals(clock to true, settings.savedNotificationPrompt)
        assertTrue(settings.notificationSystemAsked.first())
    }
}
