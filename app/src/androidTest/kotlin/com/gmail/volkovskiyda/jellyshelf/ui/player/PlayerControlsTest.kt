package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavior tests for the player controls' speed menu and transport row — stateless content, so
 * the same harness as DetailContentTest: instrumented because input dispatch needs a real
 * Android runtime (no Robolectric in this codebase by decision), and `connectedDebugAndroidTest`
 * skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Fails this class's tests on unlabelled clickables, undersized touch targets and unreadable
     * contrast — checked before every action that changes the UI, so the whole rendered tree is
     * covered, not only the nodes an assertion happens to name.
     */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun setControls(
        speed: Float = 1f,
        onSetSpeed: (Float) -> Unit = {},
        chapters: List<Chapter> = emptyList(),
        positionMs: Long = 10_000L,
        hasPrevious: Boolean = false,
        hasNext: Boolean = false,
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
        onSeek: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                PlayerControls(
                    title = "Sample video",
                    showPlay = false,
                    positionMs = positionMs,
                    durationMs = 600_000L,
                    chapters = chapters,
                    speed = speed,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    onPlayPause = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                    onSetSpeed = onSetSpeed,
                    onScrubbingChanged = {},
                    onSpeedMenuChanged = {},
                    onOpenChapters = {},
                    onBack = {},
                )
            }
        }
    }

    private fun onDescription(resId: Int) =
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(resId))

    @Test
    fun speedChip_showsTheCurrentSpeed_andTheMenuStartsClosed() {
        setControls(speed = 1.5f)

        composeRule.onNodeWithText("1.5×").assertIsDisplayed()
        composeRule.onNodeWithText("0.5×").assertDoesNotExist()
    }

    @Test
    fun pickingASpeed_appliesIt_andClosesTheMenu() {
        var applied: Float? = null
        setControls(onSetSpeed = { applied = it })

        composeRule.onNodeWithText("1×").performClick()
        composeRule.onNodeWithText("1.5×").performClick()

        assertEquals(1.5f, applied)
        // The menu is gone: none of the other options remain on screen.
        composeRule.onNodeWithText("0.75×").assertDoesNotExist()
    }

    @Test
    fun theSpeedMenu_reachesUpTo3x() {
        var applied: Float? = null
        setControls(onSetSpeed = { applied = it })

        composeRule.onNodeWithText("1×").performClick()
        // Nine options no longer all fit on a phone; the menu scrolls, so the last one is only
        // clickable after scrolling to it.
        composeRule.onNodeWithText("3×").performScrollTo().performClick()

        assertEquals(3f, applied)
    }

    @Test
    fun theSeekButtons_announceTheirAsymmetricIncrements() {
        setControls()

        // The labels are the only place the 10/30 split is visible to a screen reader — the
        // increments themselves live on the player.
        onDescription(R.string.seek_back_10).assertIsDisplayed()
        onDescription(R.string.seek_forward_30).assertIsDisplayed()
    }

    @Test
    fun theTransportArrows_areDisabledAtBothEndsOfTheQueue() {
        // A single-item queue — the media-notification path, which has no list to walk.
        setControls(hasPrevious = false, hasNext = false)

        onDescription(R.string.previous_video).assertIsNotEnabled()
        onDescription(R.string.next_video).assertIsNotEnabled()
    }

    @Test
    fun theTransportArrows_walkTheQueueFromTheMiddleOfIt() {
        var previous = 0
        var next = 0
        setControls(
            hasPrevious = true,
            hasNext = true,
            onPrevious = { previous++ },
            onNext = { next++ },
        )

        onDescription(R.string.previous_video).assertIsEnabled().performClick()
        onDescription(R.string.next_video).assertIsEnabled().performClick()

        assertEquals(1, previous)
        assertEquals(1, next)
    }

    @Test
    fun theChapterStepRow_isAbsentWithoutChapters() {
        setControls(chapters = emptyList())

        onDescription(R.string.previous_chapter).assertDoesNotExist()
        onDescription(R.string.next_chapter).assertDoesNotExist()
    }

    @Test
    fun steppingChapters_seeksToTheNeighbouringChapterStarts() {
        var seekedTo: Long? = null
        // 4 s into "Main part": past the restart threshold, so previous returns to its own start.
        setControls(chapters = chapters, positionMs = 124_000L, onSeek = { seekedTo = it })

        onDescription(R.string.previous_chapter).performClick()
        assertEquals(120_000L, seekedTo)

        onDescription(R.string.next_chapter).performClick()
        assertEquals(600_000L, seekedTo)
    }

    @Test
    fun previousChapter_isDisabledAtTheStartOfTheFirstChapter() {
        setControls(chapters = chapters, positionMs = 0L)

        onDescription(R.string.previous_chapter).assertIsNotEnabled()
        onDescription(R.string.next_chapter).assertIsEnabled()
    }

    @Test
    fun nextChapter_isDisabledInsideTheLastChapter() {
        setControls(chapters = chapters, positionMs = 610_000L)

        onDescription(R.string.next_chapter).assertIsNotEnabled()
        // Past the restart threshold into the last chapter, so previous still has work to do.
        onDescription(R.string.previous_chapter).assertIsEnabled()
    }

    private companion object {
        val chapters = listOf(
            Chapter(0L, "Intro"),
            Chapter(120_000L, "Main part"),
            Chapter(600_000L, "Outro"),
        )
    }
}
