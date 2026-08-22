package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.LOCAL_NETWORK_PERMISSION_API
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.PermissionReadout
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** A plausible instant, for the same reason as everywhere else: `0` must read as "never". */
private const val NOW = 1_800_000_000_000L

/** Past the week-long snooze, so a stored answer is stale rather than fresh. */
private val EIGHT_DAYS = TimeUnit.DAYS.toMillis(8)

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
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                LocalNetworkPermissionPrompt(
                    prompt = prompt(settings),
                    permissionExists = permissionExists,
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
     * "Allow" closes ours and hands over to Android's. Deliberately not asserted any further: the
     * result callback is what records the hand-over, and unlike `POST_NOTIFICATIONS` this
     * permission cannot be pre-granted in a test to make Android answer instantly — a real system
     * dialog would open on top, which no Compose test can reach. The launcher's contract is
     * `NotificationPermissionPromptTest`'s to pin; the same call shape is used here.
     */
    @Test
    fun allow_closesOurDialog() {
        setContent()

        composeRule.onNodeWithText(label(R.string.local_network_permission_allow)).performClick()

        title.assertDoesNotExist()
    }
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
