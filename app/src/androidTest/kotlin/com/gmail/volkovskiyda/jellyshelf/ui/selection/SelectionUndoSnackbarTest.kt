package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.InstallSnackbarHost
import com.gmail.volkovskiyda.jellyshelf.ui.LocalSnackbarHostState
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Past the 4 s Material keeps a `SnackbarDuration.Short` snackbar up, with room for the dismissal
 * on the far side. Spent on the **test clock** for the same reason [InstallProgressSnackbarTest]'s
 * budget is: the timer is a coroutine delay on the composition's dispatcher, so only a clock
 * advance retires it — `Thread.sleep` advances it by zero, and a frame-pumping `waitUntil` turns a
 * fixed interval into a wall-clock coin flip on slow hardware.
 */
private const val PAST_THE_SHORT_DURATION_MS = 6_000L

/** A swipe and its dismissal animation; nothing here is waiting on a timer. */
private const val SWIPE_TIMEOUT_MS = 2_000L

/**
 * What the Select all / Deselect all snackbar says, and where its Undo goes.
 *
 * Rendered through a bare `Scaffold` with the host provided the way `MainActivity` provides it —
 * through [LocalSnackbarHostState] — because that indirection is the thing under test: the
 * composable posts to whatever host the app put there. [SelectionUndo] values are driven by hand;
 * [VideoSelectionTest][com.gmail.volkovskiyda.jellyshelf.ui.selection.VideoSelectionTest] already
 * covers how the state machine mints them.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class SelectionUndoSnackbarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var undone = 0
    private val consumed = mutableListOf<Long>()
    private lateinit var undo: MutableState<SelectionUndo?>

    private fun setContent(initial: SelectionUndo?) {
        composeRule.setContent {
            undo = remember { mutableStateOf(initial) }
            val current by undo
            JellyshelfTheme(dynamicColor = false) {
                val hostState = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalSnackbarHostState provides hostState) {
                    SelectionUndoSnackbar(
                        undo = current,
                        onUndo = { undone++ },
                        onConsumed = { consumed += it },
                    )
                    // The empty body still has to consume the content padding, or lint reads the
                    // Scaffold as misused — there is simply nothing here to lay out but the host.
                    Scaffold(
                        snackbarHost = { InstallSnackbarHost(hostState, state = null) },
                    ) { padding ->
                        Box(Modifier.padding(padding))
                    }
                }
            }
        }
    }

    private fun label(resId: Int, vararg args: Any) = composeRule.activity.getString(resId, *args)

    private fun selectAll(id: Long, count: Int) =
        SelectionUndo(id, SelectionUndo.Kind.SELECT_ALL, previous = emptySet(), count = count)

    private fun deselectAll(id: Long) =
        SelectionUndo(id, SelectionUndo.Kind.DESELECT_ALL, previous = setOf("a", "b"), count = 0)

    /** The count is the receipt for videos picked out of the user's view. */
    @Test
    fun selectAll_reportsTheCountWithAnUndo() {
        setContent(selectAll(id = 1, count = 27))

        composeRule.onNodeWithText(label(R.string.selected_count, 27)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.undo)).assertIsDisplayed()
    }

    @Test
    fun deselectAll_saysTheSelectionWasCleared() {
        setContent(deselectAll(id = 1))

        composeRule.onNodeWithText(label(R.string.selection_cleared)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.undo)).assertIsDisplayed()
    }

    @Test
    fun nothingPending_showsNothing() {
        setContent(null)

        composeRule.onNodeWithText(label(R.string.undo)).assertDoesNotExist()
    }

    /** Undo takes the change back; it must not also consume the offer as if it went unused. */
    @Test
    fun tappingUndo_undoesInsteadOfConsuming() {
        setContent(deselectAll(id = 1))

        composeRule.onNodeWithText(label(R.string.undo)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, undone)
            assertEquals(emptyList<Long>(), consumed)
        }
    }

    /** An offer that came and went unused is consumed, so the state can drop it. */
    @Test
    fun leftAlone_theOfferIsConsumed() {
        setContent(selectAll(id = 7, count = 3))
        composeRule.onNodeWithText(label(R.string.selected_count, 3)).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(PAST_THE_SHORT_DURATION_MS)

        composeRule.onNodeWithText(label(R.string.selected_count, 3)).assertDoesNotExist()
        assertEquals(listOf(7L), consumed)
        assertEquals(0, undone)
    }

    /** Swiping the snackbar away is a dismissal like any other: consumed, not undone. */
    @Test
    fun swipedAway_theOfferIsConsumed() {
        setContent(selectAll(id = 4, count = 12))

        composeRule.onNodeWithText(label(R.string.selected_count, 12))
            .performTouchInput { swipeRight() }

        composeRule.waitUntil(timeoutMillis = SWIPE_TIMEOUT_MS) { consumed == listOf(4L) }
        composeRule.runOnIdle { assertEquals(0, undone) }
    }

    /**
     * A press that lands while the previous snackbar is still up replaces it — a stale offer to
     * undo a selection that has since changed again is worse than no offer. The first offer is
     * neither undone nor consumed: its effect is simply cancelled, and `exit()` upstream is what
     * clears an offer no snackbar resolved.
     */
    @Test
    fun aSecondPress_replacesTheFirstSnackbar() {
        setContent(selectAll(id = 1, count = 5))
        composeRule.onNodeWithText(label(R.string.selected_count, 5)).assertIsDisplayed()

        composeRule.runOnIdle { undo.value = selectAll(id = 2, count = 9) }

        composeRule.onNodeWithText(label(R.string.selected_count, 9)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.selected_count, 5)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, undone)
            assertEquals(emptyList<Long>(), consumed)
        }
    }

    /** Two presses of the same button differ only by id, and the second must still show. */
    @Test
    fun aRepeatOfTheSameKind_showsAgain() {
        setContent(deselectAll(id = 1))
        composeRule.onNodeWithText(label(R.string.undo)).performClick()
        composeRule.runOnIdle { assertEquals(1, undone) }

        composeRule.runOnIdle { undo.value = deselectAll(id = 2) }

        composeRule.onNodeWithText(label(R.string.selection_cleared)).assertIsDisplayed()
    }
}
