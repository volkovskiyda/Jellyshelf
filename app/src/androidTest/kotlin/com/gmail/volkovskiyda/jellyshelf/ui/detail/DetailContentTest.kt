package com.gmail.volkovskiyda.jellyshelf.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import com.gmail.volkovskiyda.jellyshelf.util.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavior tests for the detail screen's stateless content. Instrumented, because
 * `ui-test-junit4` needs a real Android runtime to dispatch input and there is no Robolectric in
 * this codebase (a deliberate decision — see the plan's item 17). The
 * `connectedDebugAndroidTest` task skips itself when no device is attached.
 *
 * `createAndroidComposeRule<ComponentActivity>` is what gives the test access to string
 * resources, so assertions match on the real user-visible text.
 */
@RunWith(AndroidJUnit4::class)
class DetailContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val video = Video(
        youtubeId = "1ubm7Q6DL-I",
        jellyfinItemId = "item-id",
        fileName = "sample.mp4",
        title = "Sample video",
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

    private val settings = Settings(
        serverUrl = "https://jellyfin.example.org",
        apiKey = "key",
        userId = "user",
        userName = "User",
        libraryId = "",
        libraryName = "",
        indexUrl = "",
        lastSyncAt = 0L,
        lastSyncLibraryId = "",
    )

    private fun setContent(video: Video, onRemove: () -> Unit = {}) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                DetailContent(
                    videoState = VideoDetailState.Loaded(video),
                    settings = settings,
                    fetching = false,
                    thumbnailModel = null,
                    onBack = {},
                    onPlay = { _, _ -> },
                    onOpenInJellyfin = { _, _ -> },
                    onToggleWatched = {},
                    onFetchMetadata = {},
                    onRemove = onRemove,
                )
            }
        }
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `a video the server still lists offers no remove action`() {
        setContent(video)

        composeRule.onNodeWithText(string(R.string.remove_from_library)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.missing_from_server_explained)).assertDoesNotExist()
    }

    @Test
    fun `a video missing from the server explains itself and offers removal`() {
        setContent(video.copy(missedSyncs = 1))

        composeRule.onNodeWithText(string(R.string.missing_from_server_explained)).assertIsDisplayed()
        // The detail screen scrolls; the action sits below the fold on a phone-sized viewport.
        composeRule.onNodeWithText(string(R.string.remove_from_library))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `removal is only reported once per tap`() {
        var removals = 0
        setContent(video.copy(missedSyncs = 1), onRemove = { removals++ })

        composeRule.onNodeWithText(string(R.string.remove_from_library))
            .performScrollTo()
            .performClick()

        assertEquals(1, removals)
    }

    @Test
    fun `a synced video shows when it was last synced`() {
        val syncedAt = 1_784_974_530_000L
        setContent(video.copy(lastSyncedAt = syncedAt))

        // Formatted in the device's zone, so the expectation is derived rather than hardcoded —
        // what's under test is that the line renders at all and carries the stamp.
        val expected = string(R.string.last_synced).format(formatTimestamp(syncedAt))
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun `a never-synced video shows no sync line`() {
        setContent(video.copy(lastSyncedAt = 0L))

        composeRule.onNodeWithText("Synced", substring = true).assertDoesNotExist()
    }

    /** Playback stays available: one missed sync is "probably gone", not "certainly gone". */
    @Test
    fun `a missing video can still be played`() {
        setContent(video.copy(missedSyncs = 1))

        composeRule.onNodeWithText(string(R.string.play)).assertIsEnabled()
    }
}
