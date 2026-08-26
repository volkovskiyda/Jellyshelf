package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryContent
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryVideos
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Selection mode on the library list: how it is entered, what a row does while it is on, and the
 * confirmation every action has to pass through.
 *
 * The confirmation is the point. Three of the four actions are recoverable, but the fourth deletes
 * media on the Jellyfin server over however many videos happen to be selected, and the only thing
 * between a stray tap and that is a dialog. A test that only checked the menu opened would leave
 * "tapping Remove starts a removal" unverified — which is the assertion worth having.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySelectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun video(id: String, title: String) = Video(
        youtubeId = id,
        jellyfinItemId = "item-$id",
        fileName = "$id.mp4",
        title = title,
        channel = "Sample Channel",
        channelId = null,
        durationSeconds = 754,
        uploadDate = "20260721",
        description = null,
        tags = emptyList(),
        youtubeCategories = emptyList(),
        thumbnailUrl = null,
        played = false,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = 0L,
        metadataSource = METADATA_SOURCE_INDEX,
        metadataUpdatedAt = 0L,
        missedSyncs = 0,
    )

    private val videos = listOf(video("a", "First video"), video("b", "Second video"))

    /**
     * The state the screen would hold, driven by the callbacks the content reports through.
     *
     * Compose-backed rather than plain fields: the content is stateless, so without observable
     * state a callback would change nothing on screen and every step after the first would be
     * acting on a stale frame.
     */
    private class Recorder {
        var active by mutableStateOf(false)
        var selected by mutableStateOf(setOf<String>())
        var run by mutableStateOf<SelectionRun?>(null)
        var startedAction: SelectionAction? = null
        var playlistOpened = false
        var selectAlls = 0
        var deselectAlls = 0
        var played: Video? = null
        var opened: Video? = null
    }

    private fun setContent(state: Recorder, videos: List<Video> = this.videos) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                LibraryContent(
                    videosOrNull = LibraryVideos(videos, ""),
                    query = "",
                    totalCount = videos.size,
                    onQueryChange = {},
                    onPlayVideo = { state.played = it },
                    onOpenDetails = { state.opened = it },
                    selectionActive = state.active,
                    selectedIds = state.selected,
                    selectionRun = state.run,
                    onStartSelection = { id ->
                        state.active = true
                        state.selected = setOfNotNull(id)
                    },
                    onToggleSelection = { id ->
                        state.selected =
                            if (id in state.selected) state.selected - id else state.selected + id
                    },
                    onSelectAll = { ids ->
                        state.selectAlls++
                        state.selected = state.selected + ids
                    },
                    onDeselectAll = {
                        state.deselectAlls++
                        state.selected = emptySet()
                    },
                    onExitSelection = {
                        state.active = false
                        state.selected = emptySet()
                    },
                    onSelectionAction = { state.startedAction = it },
                    onCreatePlaylist = { state.playlistOpened = true },
                    scrollStore = FakeScrollPositionRepository(),
                    thumbnailModel = { null },
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    @Test
    fun longPressingARow_entersSelectionModeWithThatVideoPicked() {
        val state = Recorder()
        setContent(state)

        composeRule.onNodeWithText("First video").performTouchInput { longClick() }

        assertEquals(setOf("a"), state.selected)
        assertEquals(true, state.active)
    }

    @Test
    fun theToolbarButton_entersSelectionModeWithNothingPicked() {
        val state = Recorder()
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.select_videos)).performClick()

        assertEquals(emptySet<String>(), state.selected)
        assertEquals(true, state.active)
    }

    @Test
    fun aRowInSelectionMode_isACheckboxRatherThanAPlayTarget() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a")
        }
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.select_video, "First video")).assertIsOn()
        composeRule.onNodeWithContentDescription(string(R.string.select_video, "Second video")).assertIsOff()

        composeRule.onNodeWithContentDescription(string(R.string.select_video, "Second video")).performClick()

        // A tap picked the video instead of opening it — the two modes never overlap.
        assertEquals(setOf("a", "b"), state.selected)
        assertNull(state.played)
        assertNull(state.opened)
    }

    @Test
    fun theBar_countsWhatIsSelectedAndOffersBothBulkButtons() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a")
        }
        setContent(state)

        composeRule.onNodeWithText(string(R.string.selected_count, 1)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(string(R.string.select_all)).performClick()
        assertEquals(1, state.selectAlls)
        assertEquals(setOf("a", "b"), state.selected)

        composeRule.onNodeWithContentDescription(string(R.string.deselect_all)).performClick()
        assertEquals(1, state.deselectAlls)
        assertEquals(emptySet<String>(), state.selected)
    }

    @Test
    fun bothBulkButtons_takeTheKeyboardOffTheSearchField() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a")
        }
        setContent(state)

        // Focus is the observable half of "the keyboard went away": the IME is up because the
        // field holds focus, so a field that still holds it after the tap is a field whose
        // keyboard is still covering the list the tap just changed.
        composeRule.onNodeWithText(string(R.string.search)).performClick()
        composeRule.onNodeWithText(string(R.string.search)).assertIsFocused()

        composeRule.onNodeWithContentDescription(string(R.string.select_all)).performClick()
        composeRule.onNodeWithText(string(R.string.search)).assertIsNotFocused()

        composeRule.onNodeWithText(string(R.string.search)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.deselect_all)).performClick()
        composeRule.onNodeWithText(string(R.string.search)).assertIsNotFocused()
    }

    @Test
    fun withEverythingAlreadySelected_selectAllIsOffered_butDoesNothingLeftToDo() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a", "b")
        }
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.select_all)).assertIsNotEnabled()
    }

    @Test
    fun withNothingSelected_theActionsMenuIsClosed() {
        val state = Recorder().apply { active = true }
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).assertIsNotEnabled()
    }

    @Test
    fun whileARunIsInFlight_theActionsMenuIsClosed() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a", "b")
            run = SelectionRun(SelectionAction.MARK_WATCHED, BulkProgress.Running(1, 2, 0))
        }
        setContent(state)

        // The repository takes one selection run at a time, so a second confirmation would be
        // accepted and then quietly do nothing.
        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).assertIsNotEnabled()
    }

    @Test
    fun aDestructiveAction_startsNothingUntilItsDialogIsConfirmed() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a", "b")
        }
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()
        composeRule.onNodeWithText(string(R.string.selection_delete_from_server)).performClick()

        // The dialog is up and nothing has run.
        composeRule.onNodeWithText(string(R.string.selection_remove_title)).assertIsDisplayed()
        assertNull(state.startedAction)

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        assertNull("dismissing must not start the run", state.startedAction)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()
        composeRule.onNodeWithText(string(R.string.selection_delete_from_server)).performClick()
        composeRule.onNodeWithText(string(R.string.remove)).performClick()

        assertEquals(SelectionAction.REMOVE, state.startedAction)
    }

    @Test
    fun everyActionIsConfirmedBeforeItRuns() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a")
        }
        setContent(state)

        for (action in listOf(R.string.mark_watched, R.string.mark_unwatched, R.string.update_metadata)) {
            composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()
            composeRule.onNodeWithText(string(action)).performClick()
            // A confirmation stands between the menu item and the run, for all of them.
            composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
            composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        }
        assertNull(state.startedAction)
    }

    /**
     * The library never offered playlists before; selection mode is what makes it possible, since
     * a playlist of "everything" was never the useful thing to build. Every list this screen shows
     * is one a playlist can be made from — the one filter that cannot is on the Categories tab.
     */
    @Test
    fun theLibrary_offersToBuildAPlaylistFromTheSelection() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a")
        }
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()

        composeRule.onNodeWithText(string(R.string.create_playlist)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.create_playlist)).performClick()
        assertTrue("the entry opens the name prompt", state.playlistOpened)
    }

    /**
     * The destructive row stays last however the menu grows — it is the furthest from the thumb
     * that opened it, and Create playlist was inserted above it rather than appended.
     */
    @Test
    fun theDestructiveRow_isTheLastThingInTheMenu() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a")
        }
        setContent(state)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()

        val playlist = composeRule.onNodeWithText(string(R.string.create_playlist))
            .getUnclippedBoundsInRoot()
        val delete = composeRule.onNodeWithText(string(R.string.selection_delete_from_server))
            .getUnclippedBoundsInRoot()
        assertTrue("delete sits above create playlist", delete.top > playlist.top)
    }

    @Test
    fun aRunningSelectionAction_reportsItsProgressWithACancel() {
        val state = Recorder().apply {
            active = true
            selected = setOf("a", "b")
            run = SelectionRun(SelectionAction.REMOVE, BulkProgress.Running(1, 2, 0))
        }
        setContent(state)

        composeRule.onNodeWithText(string(R.string.removing_progress, 1, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
    }

    @Test
    fun aFinishedSelectionAction_reportsItsFailuresWithADismiss() {
        val state = Recorder().apply {
            run = SelectionRun(SelectionAction.MARK_WATCHED, BulkProgress.Done(total = 3, failed = 1))
        }
        setContent(state)

        composeRule.onNodeWithText(string(R.string.selection_marked_watched_summary, 2, 3), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.failed_count, 1), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dismiss)).assertIsDisplayed()
    }
}
