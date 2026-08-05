package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library list and its three empty states.
 *
 * The empty states are the point: "no videos yet, go and sync" is right for an empty library and
 * wrong for a synced one whose search missed, and the branch that tells them apart reads the terms
 * tagged on the emission rather than the live query. A test that only rendered a populated list
 * would not touch any of that.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class LibraryContentTest {

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

    private fun setContent(
        videos: LibraryVideos?,
        query: String = "",
        durationFilter: DurationBucket? = null,
        totalCount: Int = 0,
        onPlayVideo: (Video) -> Unit = {},
        onOpenDetails: (Video) -> Unit = {},
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                LibraryContent(
                    videosOrNull = videos,
                    query = query,
                    durationFilter = durationFilter,
                    totalCount = totalCount,
                    onQueryChange = {},
                    onDurationFilterChange = {},
                    onPlayVideo = onPlayVideo,
                    onOpenDetails = onOpenDetails,
                    // The two seams the screen exposes for exactly this: no Koin container here.
                    scrollStore = FakeScrollPositionRepository(),
                    thumbnailModel = { null },
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    @Test
    fun aPopulatedList_showsEveryVideo() {
        setContent(
            LibraryVideos(listOf(video("a", "First video"), video("b", "Second video")), "", null),
            totalCount = 2,
        )

        composeRule.onNodeWithText("First video").assertIsDisplayed()
        composeRule.onNodeWithText("Second video").assertIsDisplayed()
    }

    @Test
    fun tappingAVideosTitle_opensItsDetails() {
        var played: Video? = null
        var opened: Video? = null
        setContent(
            LibraryVideos(listOf(video("a", "First video")), "", null),
            totalCount = 1,
            onPlayVideo = { played = it },
            onOpenDetails = { opened = it },
        )

        composeRule.onNodeWithText("First video").performClick()

        assertEquals("a", opened?.youtubeId)
        assertNull(played)
    }

    @Test
    fun tappingAVideosThumbnail_playsThatVideo() {
        var played: Video? = null
        var opened: Video? = null
        setContent(
            LibraryVideos(listOf(video("a", "First video"), video("b", "Second video")), "", null),
            totalCount = 2,
            onPlayVideo = { played = it },
            onOpenDetails = { opened = it },
        )

        composeRule
            .onNodeWithContentDescription(string(R.string.play_video, "Second video"))
            .performClick()

        assertEquals("b", played?.youtubeId)
        assertNull(opened)
    }

    @Test
    fun theThumbnailOfAnUnplayableVideo_opensItsDetailsInstead() {
        // No Jellyfin item behind the row: there is nothing for the player to stream, so the
        // thumbnail sends the user where the state is explained rather than into a doomed player.
        var played: Video? = null
        var opened: Video? = null
        setContent(
            LibraryVideos(listOf(video("a", "First video").copy(jellyfinItemId = null)), "", null),
            totalCount = 1,
            onPlayVideo = { played = it },
            onOpenDetails = { opened = it },
        )

        composeRule
            .onNodeWithContentDescription(string(R.string.open_video_details, "First video"))
            .performClick()

        assertEquals("a", opened?.youtubeId)
        assertNull(played)
    }

    @Test
    fun anEmptyLibrary_saysToSync() {
        setContent(LibraryVideos(emptyList(), "", null))

        composeRule.onNodeWithText(string(R.string.empty_library)).assertIsDisplayed()
    }

    @Test
    fun aSearchThatMatchedNothing_namesTheQueryInsteadOfSuggestingASync() {
        setContent(LibraryVideos(emptyList(), "mafia", null), query = "mafia", totalCount = 879)

        composeRule.onNodeWithText(string(R.string.no_videos_match, "mafia")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.empty_library)).assertDoesNotExist()
    }

    @Test
    fun aFilterThatMatchedNothing_namesTheBucket() {
        val bucket = DurationBucket.OVER_60
        setContent(
            LibraryVideos(emptyList(), "", bucket),
            durationFilter = bucket,
            totalCount = 879,
        )

        composeRule
            .onNodeWithText(string(R.string.empty_duration_filter, bucket.label))
            .assertIsDisplayed()
    }

    @Test
    fun aSearchAndAFilterThatMatchedNothing_nameBoth() {
        val bucket = DurationBucket.OVER_60
        setContent(
            LibraryVideos(emptyList(), "mafia", bucket),
            query = "mafia",
            durationFilter = bucket,
            totalCount = 879,
        )

        composeRule
            .onNodeWithText(string(R.string.no_videos_match_duration, bucket.label, "mafia"))
            .assertIsDisplayed()
    }

    @Test
    fun theEmptyStateFollowsTheEmission_notTheLiveQuery() {
        // The frame after the query is cleared: the live query is already blank, but the list on
        // screen is still the searched one. Describing it as an empty library would be wrong.
        setContent(LibraryVideos(emptyList(), "mafia", null), query = "", totalCount = 879)

        composeRule.onNodeWithText(string(R.string.no_videos_match, "mafia")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.empty_library)).assertDoesNotExist()
    }
}
