package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One category's videos, and the two bulk actions that are scoped to particular filters.
 *
 * "Fetch metadata for N missing" belongs to Uncategorized and bulk removal to Watched — showing
 * either on an ordinary category would offer a destructive action against the wrong set, which is
 * what these pin.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class CategoryVideosContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun video(id: String, title: String, played: Boolean = false) = Video(
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
        missedSyncs = 0,
    )

    private val videos = listOf(video("a", "First video"), video("b", "Second video"))

    private fun setContent(
        videosOrNull: List<Video>?,
        isUncategorized: Boolean = false,
        isWatched: Boolean = false,
        bulkFetch: BulkProgress = BulkProgress.Idle,
        bulkRemove: BulkProgress = BulkProgress.Idle,
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                CategoryVideosContent(
                    title = CATEGORY_TITLE,
                    videosOrNull = videosOrNull,
                    bulkFetch = bulkFetch,
                    bulkRemove = bulkRemove,
                    creating = false,
                    isUncategorized = isUncategorized,
                    isWatched = isWatched,
                    scrollKey = "category-test",
                    showDialog = false,
                    onShowDialog = {},
                    onDismissDialog = {},
                    showRemoveDialog = false,
                    onShowRemoveDialog = {},
                    onDismissRemoveDialog = {},
                    onVideoClick = {},
                    onBack = {},
                    onStartFetchMissing = {},
                    onCancelFetchMissing = {},
                    onAcknowledgeBulkFetch = {},
                    onConfirmRemoveWatched = {},
                    onCancelRemoveWatched = {},
                    onAcknowledgeBulkRemove = {},
                    onCreatePlaylist = {},
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

    @Test
    fun anOrdinaryCategory_offersNeitherBulkAction() {
        setContent(videos)

        composeRule.onNodeWithText(string(R.string.fetch_missing, videos.size)).assertDoesNotExist()
        composeRule.onNodeWithText(removeWatchedLabel(videos.size)).assertDoesNotExist()
    }

    @Test
    fun theUncategorizedFilter_offersTheMetadataFetch() {
        setContent(videos, isUncategorized = true)

        composeRule.onNodeWithText(string(R.string.fetch_missing, videos.size)).assertIsDisplayed()
        composeRule.onNodeWithText(removeWatchedLabel(videos.size)).assertDoesNotExist()
    }

    @Test
    fun theWatchedFilter_offersBulkRemovalInstead() {
        val watched = listOf(video("a", "First video", played = true))
        setContent(watched, isWatched = true)

        composeRule.onNodeWithText(removeWatchedLabel(watched.size)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.fetch_missing, watched.size)).assertDoesNotExist()
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

    @Test
    fun aPendingFirstEmission_showsNoEmptyGuidance() {
        setContent(videosOrNull = null)

        composeRule.onNodeWithText(string(R.string.empty_category)).assertDoesNotExist()
    }

    private fun removeWatchedLabel(count: Int) =
        composeRule.activity.resources.getQuantityString(R.plurals.remove_watched, count, count)

    private companion object {
        // Deliberately not the videos' channel name, which every row also renders.
        const val CATEGORY_TITLE = "A category of its own"
    }
}
