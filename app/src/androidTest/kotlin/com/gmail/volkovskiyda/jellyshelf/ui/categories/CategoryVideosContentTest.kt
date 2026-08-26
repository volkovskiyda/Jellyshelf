package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One category's videos, and the bulk actions that are scoped to particular filters.
 *
 * "Fetch metadata for N missing" belongs to Uncategorized, and each of the two removals to its own
 * filter ([RemoveKind]) — showing any of them on an ordinary category would offer a destructive
 * action against the wrong set, which is what these pin.
 *
 * The two removals get more attention than their one-line difference suggests, because they are
 * adjacent filters whose confirmations say opposite things: Watched deletes the media on the
 * Jellyfin server and cannot be undone, while Missing from server only drops local rows for videos
 * the server has already stopped listing. Showing one filter's wording over the other's action is
 * the mistake worth testing for.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class CategoryVideosContentTest {

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

    private fun video(id: String, title: String, played: Boolean = false, missedSyncs: Int = 0) = Video(
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
        played = played,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = 0L,
        metadataSource = METADATA_SOURCE_INDEX,
        metadataUpdatedAt = 0L,
        missedSyncs = missedSyncs,
    )

    private val videos = listOf(video("a", "First video"), video("b", "Second video"))

    @Suppress("LongParameterList") // one knob per piece of state the content renders
    private fun setContent(
        videosOrNull: List<Video>?,
        isUncategorized: Boolean = false,
        removeKind: RemoveKind? = null,
        bulkFetch: BulkProgress = BulkProgress.Idle,
        bulkRemove: BulkProgress = BulkProgress.Idle,
        demoMode: Boolean = false,
        showRemoveDialog: Boolean = false,
        selectionActive: Boolean = false,
        playlistDialogOpen: Boolean = false,
        onConfirmRemove: () -> Unit = {},
        onPlayVideo: (Video) -> Unit = {},
        onOpenDetails: (Video) -> Unit = {},
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                CategoryVideosContent(
                    title = CATEGORY_TITLE,
                    videosOrNull = videosOrNull,
                    bulkFetch = bulkFetch,
                    bulkRemove = bulkRemove,
                    demoMode = demoMode,
                    isUncategorized = isUncategorized,
                    removeKind = removeKind,
                    scrollKey = "category-test",
                    showRemoveDialog = showRemoveDialog,
                    onShowRemoveDialog = {},
                    onDismissRemoveDialog = {},
                    onPlayVideo = onPlayVideo,
                    onOpenDetails = onOpenDetails,
                    onBack = {},
                    onStartFetchMissing = {},
                    onCancelFetchMissing = {},
                    onAcknowledgeBulkFetch = {},
                    onConfirmRemove = onConfirmRemove,
                    onCancelRemove = {},
                    onAcknowledgeBulkRemove = {},
                    onCreatePlaylist = {},
                    playlistDialogOpen = playlistDialogOpen,
                    selectionActive = selectionActive,
                    selectedIds = if (selectionActive) setOf(videos.first().youtubeId) else emptySet(),
                    scrollStore = FakeScrollPositionRepository(),
                    thumbnailModel = { null },
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    @Test
    fun theCategorysVideos_areListedUnderItsName() {
        setContent(videos)

        composeRule.onNodeWithText(CATEGORY_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("First video").assertIsDisplayed()
        composeRule.onNodeWithText("Second video").assertIsDisplayed()
    }

    /**
     * The category's own bulk runs act on the whole filter, not on a selection — and they sit one
     * strip below the selection bar, where "Remove 12 watched videos" under "12 selected" reads as
     * the button for those 12. On the Watched filter the two counts even agree. One of them
     * deletes media on the server, so the offer is withdrawn for as long as the resemblance could
     * be acted on.
     */
    @Test
    fun whileSelecting_theCategorysOwnBulkOfferIsWithdrawn() {
        setContent(videos, removeKind = RemoveKind.WATCHED, selectionActive = true)

        composeRule.onNodeWithText(removeLabel(RemoveKind.WATCHED, videos.size)).assertDoesNotExist()
    }

    /**
     * A run already under way keeps its header even while selecting: that strip is the only place
     * to watch it or cancel it, and entering selection mode must not strand a removal of the whole
     * filter with no way to stop it.
     */
    @Test
    fun whileSelecting_aRunningCategoryRemovalKeepsItsProgressAndCancel() {
        setContent(
            videos,
            removeKind = RemoveKind.WATCHED,
            bulkRemove = BulkProgress.Running(done = 1, total = 2, failed = 0),
            selectionActive = true,
        )

        composeRule.onNodeWithText(string(R.string.removing_progress, 1, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
    }

    @Test
    fun anOrdinaryCategory_offersNoBulkAction() {
        setContent(videos)

        composeRule.onNodeWithText(string(R.string.fetch_missing, videos.size)).assertDoesNotExist()
        composeRule.onNodeWithText(removeLabel(RemoveKind.WATCHED, videos.size)).assertDoesNotExist()
        composeRule.onNodeWithText(removeLabel(RemoveKind.MISSING, videos.size)).assertDoesNotExist()
    }

    @Test
    fun theUncategorizedFilter_offersTheMetadataFetch() {
        setContent(videos, isUncategorized = true)

        composeRule.onNodeWithText(string(R.string.fetch_missing, videos.size)).assertIsDisplayed()
        composeRule.onNodeWithText(removeLabel(RemoveKind.WATCHED, videos.size)).assertDoesNotExist()
        composeRule.onNodeWithText(removeLabel(RemoveKind.MISSING, videos.size)).assertDoesNotExist()
    }

    @Test
    fun theWatchedFilter_offersBulkRemovalInstead() {
        val watched = listOf(video("a", "First video", played = true))
        setContent(watched, removeKind = RemoveKind.WATCHED)

        composeRule.onNodeWithText(removeLabel(RemoveKind.WATCHED, watched.size)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.fetch_missing, watched.size)).assertDoesNotExist()
        composeRule.onNodeWithText(removeLabel(RemoveKind.MISSING, watched.size)).assertDoesNotExist()
    }

    /**
     * The new filter's own removal. Asserting the *watched* label is absent is the substance here:
     * both headers are a plural over the same count in the same red button, so a mis-wired
     * [RemoveKind] would look right and read wrong.
     */
    @Test
    fun theMissingFilter_offersItsOwnRemovalAndNotTheWatchedOne() {
        val missing = listOf(video("a", "First video", missedSyncs = 1))
        setContent(missing, removeKind = RemoveKind.MISSING)

        composeRule.onNodeWithText(removeLabel(RemoveKind.MISSING, missing.size)).assertIsDisplayed()
        composeRule.onNodeWithText(removeLabel(RemoveKind.WATCHED, missing.size)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.fetch_missing, missing.size)).assertDoesNotExist()
    }

    /**
     * A playlist is built from Jellyfin item ids, and every video on this filter is one the server
     * has stopped listing — the call can only be rejected. Offering an action that cannot succeed
     * is worse than not offering it, so the entry is absent from the selection menu entirely
     * rather than present and failing.
     */
    @Test
    fun theMissingFilter_doesNotOfferToBuildAPlaylist() {
        val missing = listOf(video("a", "First video", missedSyncs = 1))
        setContent(missing, removeKind = RemoveKind.MISSING, selectionActive = true)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()

        composeRule.onNodeWithText(string(R.string.create_playlist)).assertDoesNotExist()
    }

    @Test
    fun everyOtherCategory_stillOffersThePlaylist() {
        setContent(videos, selectionActive = true)

        composeRule.onNodeWithContentDescription(string(R.string.selection_actions)).performClick()

        composeRule.onNodeWithText(string(R.string.create_playlist)).assertIsDisplayed()
    }

    /** The prompt is named for the category, and counts the selection rather than the category. */
    @Test
    fun thePlaylistPrompt_isNamedForTheCategoryAndCountsTheSelection() {
        setContent(videos, selectionActive = true, playlistDialogOpen = true)

        // The harness selects exactly one video; the category holds more.
        composeRule.onNodeWithText(videoCount(1), substring = true).assertIsDisplayed()
    }

    /**
     * The count sits at the end of the bar with a button after it, and that button's own padding is
     * what holds it off the edge of the screen. Create playlist used to be that button and is now
     * in the selection menu; Select took its place, and is offered wherever the count is. This
     * asserts the geometry rather than the modifier, so it holds however the inset is applied.
     */
    @Test
    fun theCount_keepsItsInsetFromTheEdgeOfTheScreen() {
        val missing = listOf(video("a", "First video", missedSyncs = 1))
        setContent(missing, removeKind = RemoveKind.MISSING)

        val count = composeRule.onNodeWithText(videoCount(missing.size)).getUnclippedBoundsInRoot()
        val screen = composeRule.onRoot().getUnclippedBoundsInRoot()
        val inset = screen.right - count.right
        assertTrue("the count sits ${'$'}inset from the edge", inset >= 12.dp)
    }

    /** Every row on this filter carries the notice, which is what put it here in the first place. */
    @Test
    fun theMissingFilter_rowsStillSayTheyAreMissingFromTheServer() {
        setContent(listOf(video("a", "First video", missedSyncs = 1)), removeKind = RemoveKind.MISSING)

        composeRule.onNodeWithText(string(R.string.missing_from_server)).assertIsDisplayed()
    }

    /**
     * The two confirmations are the last thing standing between "drop some local rows" and "delete
     * the media off the server", so each has to say which one it is — and neither may say the
     * other's sentence.
     */
    @Test
    fun theMissingConfirmation_promisesALocalRemovalAndNoServerDelete() {
        val missing = listOf(video("a", "First video", missedSyncs = 1))
        setContent(missing, removeKind = RemoveKind.MISSING, showRemoveDialog = true)

        composeRule.onNodeWithText(string(R.string.remove_missing_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText(dialogText(R.string.remove_missing_dialog_text, missing.size))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.remove_watched_dialog_title)).assertDoesNotExist()
        composeRule.onNodeWithText(dialogText(R.string.remove_watched_dialog_text, missing.size))
            .assertDoesNotExist()
    }

    @Test
    fun theWatchedConfirmation_stillWarnsAboutTheServerDelete() {
        val watched = listOf(video("a", "First video", played = true))
        setContent(watched, removeKind = RemoveKind.WATCHED, showRemoveDialog = true)

        composeRule.onNodeWithText(dialogText(R.string.remove_watched_dialog_text, watched.size))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.remove_missing_dialog_title)).assertDoesNotExist()
    }

    /**
     * The demo wording belongs to the watched removal alone — it exists to stop that one promising
     * a server delete a demo install cannot perform. The missing removal never claimed one, so a
     * demo must not swap its honest text for a sentence about the demo library.
     */
    @Test
    fun theMissingConfirmation_isUnchangedByDemoMode() {
        val missing = listOf(video("a", "First video", missedSyncs = 1))
        setContent(missing, removeKind = RemoveKind.MISSING, showRemoveDialog = true, demoMode = true)

        composeRule.onNodeWithText(dialogText(R.string.remove_missing_dialog_text, missing.size))
            .assertIsDisplayed()
        composeRule.onNodeWithText(dialogText(R.string.remove_watched_dialog_text_demo, missing.size))
            .assertDoesNotExist()
    }

    @Test
    fun confirmingTheMissingRemoval_startsTheRun() {
        var confirms = 0
        setContent(
            listOf(video("a", "First video", missedSyncs = 1)),
            removeKind = RemoveKind.MISSING,
            showRemoveDialog = true,
            onConfirmRemove = { confirms++ },
        )

        composeRule.onNodeWithText(string(R.string.remove)).performClick()

        assertEquals(1, confirms)
    }

    /** Progress and the summary come from the same header both removals share. */
    @Test
    fun aRunningMissingRemoval_showsProgressAndACancel() {
        setContent(
            listOf(video("a", "First video", missedSyncs = 1)),
            removeKind = RemoveKind.MISSING,
            bulkRemove = BulkProgress.Running(done = 1, total = 3, failed = 0),
        )

        composeRule.onNodeWithText(string(R.string.removing_progress, 1, 3)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(removeLabel(RemoveKind.MISSING, 1)).assertDoesNotExist()
    }

    @Test
    fun anEmptyUncategorized_saysEverythingHasMetadata() {
        setContent(emptyList(), isUncategorized = true)

        composeRule.onNodeWithText(string(R.string.empty_uncategorized)).assertIsDisplayed()
    }

    @Test
    fun anEmptyCategory_saysSoWithoutTheUncategorizedWording() {
        setContent(emptyList())

        composeRule.onNodeWithText(string(R.string.empty_category)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.empty_uncategorized)).assertDoesNotExist()
    }

    /**
     * Reached by emptying the filter from this very screen — the Others tab hides a filter whose
     * count is zero, so "No videos in this category" would be the wrong last word on a removal the
     * user just performed.
     */
    @Test
    fun anEmptiedMissingFilter_saysNothingIsMissingAnyMore() {
        setContent(emptyList(), removeKind = RemoveKind.MISSING)

        composeRule.onNodeWithText(string(R.string.empty_missing)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.empty_category)).assertDoesNotExist()
    }

    @Test
    fun aPendingFirstEmission_showsNoEmptyGuidance() {
        setContent(videosOrNull = null)

        composeRule.onNodeWithText(string(R.string.empty_category)).assertDoesNotExist()
    }

    /**
     * The row's two targets, here as well as in the library: a category list is the other way into
     * a video, and it queues the category rather than the library once one starts playing.
     */
    @Test
    fun aRowsThumbnailPlays_whileItsTitleOpensTheDetails() {
        var played: Video? = null
        var opened: Video? = null
        setContent(videos, onPlayVideo = { played = it }, onOpenDetails = { opened = it })

        composeRule
            .onNodeWithContentDescription(string(R.string.play_video, "First video"))
            .performClick()
        composeRule.onNodeWithText("Second video").performClick()

        assertEquals("a", played?.youtubeId)
        assertEquals("b", opened?.youtubeId)
    }

    private fun removeLabel(kind: RemoveKind, count: Int) =
        composeRule.activity.resources.getQuantityString(kind.idleLabel, count, count)

    private fun videoCount(count: Int) =
        composeRule.activity.resources.getQuantityString(R.plurals.video_count, count, count)

    /** A removal confirmation's body, whose one argument is itself the pluralized video count. */
    private fun dialogText(id: Int, count: Int) = string(id, videoCount(count))

    private companion object {
        // Deliberately not the videos' channel name, which every row also renders.
        const val CATEGORY_TITLE = "A category of its own"
    }
}
