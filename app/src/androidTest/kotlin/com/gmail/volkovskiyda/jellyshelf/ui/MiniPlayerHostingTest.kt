package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.NotificationPermissionRule
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlaying
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

private const val TITLE = "Minimized video"

/** The bar is a window, like the update dialog: it lands a frame or two after the composition. */
private const val BAR_TIMEOUT_MS = 5_000L

/**
 * Where the mini-player bar is allowed to appear, through the real [MainActivity] wiring.
 *
 * No Koin override: [NowPlayingState] takes no dependencies, so the test writes into the app's own
 * single exactly as [PlaybackService][com.gmail.volkovskiyda.jellyshelf.playback.PlaybackService]
 * would. That also sidesteps the override trap this suite has been bitten by — `unloadKoinModules`
 * removes definitions by key and would take the app's own with it.
 */
@RunWith(AndroidJUnit4::class)
class MiniPlayerHostingTest {

    @get:Rule(order = 0)
    val notificationPermission = NotificationPermissionRule()

    /**
     * [UnconfinedTestDispatcher] rather than the v2 default of `StandardTestDispatcher`, for the
     * reason LiveUiJourneyTest gives: the default drains composition coroutines on the thread
     * running the test rather than the main one, and one test here opens the player, whose
     * `MediaController` rejects every call made off the application thread. Unconfined resumes
     * inline on the thread that composed, which is the main thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>(effectContext = UnconfinedTestDispatcher())

    private val nowPlaying: NowPlayingState by lazy { GlobalContext.get().get() }

    /** The single outlives the activity, so a test that left something playing would leak it. */
    @After
    fun clearNowPlaying() {
        nowPlaying.detach()
    }

    private fun label(resId: Int) = composeRule.activity.getString(resId)

    private fun startPlaying(isPlaying: Boolean = true, hasNext: Boolean = true) {
        nowPlaying.show(
            NowPlaying(
                youtubeId = "aaaaaaaaaaa",
                title = TITLE,
                artworkUri = null,
                isPlaying = isPlaying,
                hasNext = hasNext,
            ),
        )
        composeRule.waitForIdle()
    }

    private fun awaitBar() {
        composeRule.waitUntil(BAR_TIMEOUT_MS) { composeRule.onNodeWithText(TITLE).isDisplayed() }
    }

    @Test
    fun nothingPlaying_showsNoBar() {
        composeRule.onNodeWithText(TITLE).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label(R.string.mini_player_stop))
            .assertDoesNotExist()
    }

    @Test
    fun somethingPlaying_showsTheBarOverTheLibrary() {
        startPlaying()

        awaitBar()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.mini_player_pause))
            .assertIsDisplayed()
    }

    /** The queue emptying — which is what a stop leaves behind — takes the bar with it. */
    @Test
    fun theBarGoesAwayWhenTheQueueEmpties() {
        startPlaying()
        awaitBar()

        nowPlaying.show(null)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TITLE).assertDoesNotExist()
    }

    /**
     * Tapping the row reopens the player — and the bar must not follow it there. The player is the
     * one screen the bar is wrong on: it would sit under a full-screen video advertising the video
     * above it, with a stop button beside the controls that already stop.
     *
     * Reaching the player through the bar rather than pushing the key directly is deliberate: the
     * row's tap target is the feature, and this is the only test that exercises it end to end.
     */
    @Test
    fun tappingTheRow_opensThePlayer_andTheBarStaysBehind() {
        startPlaying()
        awaitBar()

        composeRule.onNodeWithText(TITLE).performClick()
        composeRule.waitForIdle()

        // The player screen is up: its back affordance is over the video, and the bar is gone.
        composeRule.waitUntil(BAR_TIMEOUT_MS) {
            !composeRule.onNodeWithContentDescription(label(R.string.mini_player_stop)).isDisplayed()
        }
        composeRule.onNodeWithContentDescription(label(R.string.mini_player_stop))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label(R.string.back)).assertIsDisplayed()
    }

    /**
     * The bar's buttons have to reach the *service's* player, not a re-implementation of its rules
     * inside the UI. Asserting the hosting passes the taps straight through to the registered
     * transport is how that stays true — the sequence itself is the service's business, and
     * PlaybackService is where it is written down.
     */
    @Test
    fun theBarsButtons_reachTheRegisteredTransport() {
        val calls = mutableListOf<String>()
        nowPlaying.attach(object : NowPlayingState.Transport {
            override fun playPause() {
                calls += "playPause"
            }
            override fun next() {
                calls += "next"
            }
            override fun stop() {
                calls += "stop"
            }
        })
        startPlaying()
        awaitBar()

        composeRule.onNodeWithContentDescription(label(R.string.mini_player_pause)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(label(R.string.next_video)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(label(R.string.mini_player_stop)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("playPause", "next", "stop"), calls)
    }

    /**
     * The queue's far end reaches the button through [NowPlaying.hasNext] alone — the bar has no
     * player to ask, by design. Asserting only the enabled state, not a tap: no service is running
     * here, so the transport is unattached and `next()` is a no-op.
     *
     * One test flipping the field rather than two, because `show` may be called repeatedly on a
     * rule whose content was set once by [MainActivity] itself.
     */
    @Test
    fun theNextButton_followsTheQueuesFarEnd() {
        startPlaying(hasNext = false)
        awaitBar()

        composeRule.onNodeWithContentDescription(label(R.string.next_video)).assertIsNotEnabled()

        nowPlaying.setHasNext(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(label(R.string.next_video)).assertIsEnabled()
    }

    /**
     * The service's progress ticks reach the bar's line through the real hosting — the same
     * [NowPlayingState.setProgress] path the playback service's ticker writes once a second.
     * MiniPlayerBarTest pins what the stateless bar draws for a given fraction; this pins that a
     * *changing* fraction actually redraws it here, which is the half a stateless test cannot see.
     *
     * The fractions are deliberately odd ones, and the matcher is by exact value: the library
     * behind the bar draws its own watched-progress lines on video rows, so "any progress bar"
     * would be ambiguous on a populated install. A row does not move between the two assertions,
     * and only the bar is being fed these two values.
     */
    @Test
    fun theProgressLine_followsTheServiceTicks() {
        startPlaying()
        awaitBar()

        nowPlaying.setProgress(positionMs = 37_000L, durationMs = 100_000L)
        composeRule.waitForIdle()
        composeRule.onNode(progressAt(0.37f)).assertExists()

        nowPlaying.setProgress(positionMs = 83_000L, durationMs = 100_000L)
        composeRule.waitForIdle()
        composeRule.onNode(progressAt(0.83f)).assertExists()
        composeRule.onNode(progressAt(0.37f)).assertDoesNotExist()
    }

    private fun progressAt(fraction: Float) = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo,
        ProgressBarRangeInfo(fraction, 0f..1f),
    )
}
