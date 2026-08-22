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
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

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
        onBack: () -> Unit = {},
        onMinimize: () -> Unit = {},
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
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
                    onBack = onBack,
                    onMinimize = onMinimize,
                )
            }
        }
    }

    private fun onDescription(resId: Int) =
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(resId))

    /**
     * The label the chip and the menu actually render for [speed], built the way `formatSpeed`
     * builds it rather than written out as `"1.5×"`.
     *
     * `NumberFormat` is locale-sensitive, so a hard-coded separator only matches devices whose
     * locale happens to use it: on a `uk-UA` phone the app renders `"1,5×"` and every assertion
     * looking for `"1.5×"` fails, while the `assertDoesNotExist` lines beside them pass
     * **vacuously** — they assert the absence of a string that was never on screen, so a real
     * regression would go through green. Locales with non-Latin digits break the whole-number
     * labels too, which is why `1×` and `3×` come through here as well rather than only the
     * fractional ones.
     */
    private fun speedLabel(speed: Float): String =
        composeRule.activity.getString(
            R.string.playback_speed_value,
            NumberFormat.getNumberInstance().format(speed.toDouble()),
        )

    /**
     * The two exits are separate controls doing separate things: back stops playback (its callback
     * is the one that calls stopPlayback), minimize leaves it running. A single button wired to
     * both, or either wired to the other's callback, is the failure this catches — and it would be
     * silent, because both look like "the player closed".
     */
    @Test
    fun theTopBar_offersBackAndMinimizeSeparately() {
        var backs = 0
        var minimizes = 0
        setControls(onBack = { backs++ }, onMinimize = { minimizes++ })

        onDescription(R.string.player_minimize).performClick()

        assertEquals(0, backs)
        assertEquals(1, minimizes)

        onDescription(R.string.back).performClick()

        assertEquals(1, backs)
        assertEquals(1, minimizes)
    }

    @Test
    fun speedChip_showsTheCurrentSpeed_andTheMenuStartsClosed() {
        setControls(speed = 1.5f)

        composeRule.onNodeWithText(speedLabel(1.5f)).assertIsDisplayed()
        composeRule.onNodeWithText(speedLabel(0.5f)).assertDoesNotExist()
    }

    @Test
    fun pickingASpeed_appliesIt_andClosesTheMenu() {
        var applied: Float? = null
        setControls(onSetSpeed = { applied = it })

        composeRule.onNodeWithText(speedLabel(1f)).performClick()
        composeRule.onNodeWithText(speedLabel(1.5f)).performClick()

        assertEquals(1.5f, applied)
        // The menu is gone: none of the other options remain on screen.
        composeRule.onNodeWithText(speedLabel(0.75f)).assertDoesNotExist()
    }

    @Test
    fun theSpeedMenu_reachesUpTo3x() {
        var applied: Float? = null
        setControls(onSetSpeed = { applied = it })

        composeRule.onNodeWithText(speedLabel(1f)).performClick()
        // Nine options no longer all fit on a phone; the menu scrolls, so the last one is only
        // clickable after scrolling to it.
        composeRule.onNodeWithText(speedLabel(3f)).performScrollTo().performClick()

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
