package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mini-player row on its own — stateless content, so the same harness as PlayerControlsTest.
 *
 * The accessibility checks matter more here than usual: every one of the row's four targets is an
 * icon or the row itself, so none of them has a visible label to fall back on.
 */
@RunWith(AndroidJUnit4::class)
class MiniPlayerBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun setBar(
        title: String? = "Sample video",
        isPlaying: Boolean = true,
        hasNext: Boolean = true,
        progress: Float? = null,
        onOpen: () -> Unit = {},
        onPlayPause: () -> Unit = {},
        onNext: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                MiniPlayerBar(
                    title = title,
                    artworkUri = null,
                    isPlaying = isPlaying,
                    hasNext = hasNext,
                    progress = progress,
                    onOpen = onOpen,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onStop = onStop,
                )
            }
        }
    }

    private fun onDescription(resId: Int) =
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(resId))

    @Test
    fun theBar_showsTheTitleOfWhatIsPlaying() {
        setBar()

        composeRule.onNodeWithText("Sample video").assertIsDisplayed()
    }

    /**
     * The icon has to describe the *action*, not the state: a bar showing a pause icon while the
     * video is paused is the classic version of this bug, and it announces itself to TalkBack long
     * before anyone notices the glyph.
     *
     * Two tests rather than one that re-renders, because `setContent` may be called only once per
     * rule — a second call throws "has already set content" rather than replacing what is there.
     */
    @Test
    fun theIcon_offersPause_whilePlaying() {
        setBar(isPlaying = true)

        onDescription(R.string.mini_player_pause).assertIsDisplayed()
        onDescription(R.string.mini_player_play).assertDoesNotExist()
    }

    @Test
    fun theIcon_offersPlay_whilePaused() {
        setBar(isPlaying = false)

        onDescription(R.string.mini_player_play).assertIsDisplayed()
        onDescription(R.string.mini_player_pause).assertDoesNotExist()
    }

    @Test
    fun eachControl_firesItsOwnCallback() {
        var opens = 0
        var playPauses = 0
        var nexts = 0
        var stops = 0
        setBar(
            onOpen = { opens++ },
            onPlayPause = { playPauses++ },
            onNext = { nexts++ },
            onStop = { stops++ },
        )

        onDescription(R.string.mini_player_pause).performClick()
        assertEquals(listOf(0, 1, 0, 0), listOf(opens, playPauses, nexts, stops))

        onDescription(R.string.next_video).performClick()
        assertEquals(listOf(0, 1, 1, 0), listOf(opens, playPauses, nexts, stops))

        onDescription(R.string.mini_player_stop).performClick()
        assertEquals(listOf(0, 1, 1, 1), listOf(opens, playPauses, nexts, stops))
    }

    /** Dimmed rather than hidden at the end of the queue, so Stop never moves under a finger. */
    @Test
    fun theNextButton_isDisabled_onTheLastVideo() {
        setBar(hasNext = false)

        onDescription(R.string.next_video).assertIsNotEnabled()
    }

    /**
     * The dimmed button sits inside the row's own `clickable`, so the obvious worry is that a tap
     * on it falls through and opens the player — the opposite of what dimming promises. Foundation
     * consumes the down event before checking `enabled`, so it does not; this pins that, because it
     * is one line of library behaviour standing between "dimmed" and "navigates unexpectedly".
     */
    @Test
    fun aDisabledNextButton_doesNotOpenThePlayer() {
        var opens = 0
        var nexts = 0
        setBar(hasNext = false, onOpen = { opens++ }, onNext = { nexts++ })

        onDescription(R.string.next_video).performClick()

        assertEquals(listOf(0, 0), listOf(opens, nexts))
    }

    /**
     * The row is the large target for the common action. The three buttons sit inside it, so the
     * risk runs the other way as well: a button press that also opened the player would pause and
     * navigate at once.
     */
    @Test
    fun theRow_opensThePlayer_andTheButtonsInsideItDoNot() {
        var opens = 0
        var playPauses = 0
        setBar(onOpen = { opens++ }, onPlayPause = { playPauses++ })

        composeRule.onNodeWithText("Sample video").performClick()

        assertEquals(1, opens)
        assertEquals(0, playPauses)
    }

    /** A row that never loaded a title still has to be a legal, tappable row. */
    @Test
    fun aMissingTitle_leavesTheControlsUsable() {
        var opens = 0
        setBar(title = null, onOpen = { opens++ })

        onDescription(R.string.mini_player_pause).assertIsDisplayed()
        onDescription(R.string.mini_player_stop).performClick()
        assertEquals(0, opens)
    }

    /**
     * A duration the player has not reported yet must draw **no line at all**, not a line at zero.
     *
     * The two are one `?:` apart in the caller and look identical in a screenshot — an empty track
     * reads as "this video is at the very start", which is a confident lie about a video that may
     * be halfway through and merely still opening. Pinned here because nothing else would catch it.
     */
    @Test
    fun unknownDuration_drawsNoProgressLine() {
        setBar(progress = null)

        composeRule.onNode(progressBar).assertDoesNotExist()
    }

    @Test
    fun knownDuration_drawsTheProgressLine() {
        setBar(progress = 0.5f)

        composeRule.onNode(progressBar).assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
    }

    /** Past the end — a position report that outran the duration — clamps rather than overflowing. */
    @Test
    fun progressIsClampedToTheTrack() {
        setBar(progress = 1f)

        composeRule.onNode(progressBar).assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
    }

    private val progressBar =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}
