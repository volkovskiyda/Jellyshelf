package com.gmail.volkovskiyda.jellyshelf.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_ITEM_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import com.gmail.volkovskiyda.jellyshelf.util.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Before
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

    /**
     * Fails this class's tests on unlabelled clickables, undersized touch targets and unreadable
     * contrast — checked before every action that changes the UI, so the whole rendered tree is
     * covered, not only the nodes an assertion happens to name.
     */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

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
        accessToken = "",
        userId = "user",
        userName = "User",
        libraryId = "",
        libraryName = "",
        indexUrl = "",
        lastSyncAt = 0L,
        lastSyncLibraryId = "",
    )

    private fun setContent(
        video: Video,
        onPlay: (Video, Settings, PlaybackMode) -> Unit = { _, _, _ -> },
        onSelectMode: (PlaybackMode) -> Unit = {},
        onRemove: () -> Unit = {},
        now: Long = NOW,
        settings: Settings = this.settings,
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                DetailContent(
                    videoState = VideoDetailState.Loaded(video),
                    settings = settings,
                    fetching = false,
                    thumbnailModel = null,
                    onBack = {},
                    onPlay = onPlay,
                    onSelectMode = onSelectMode,
                    onToggleWatched = {},
                    onFetchMetadata = {},
                    onRemove = onRemove,
                    // Pinned: sync times are relative now, so the wall clock would decide what
                    // this renders.
                    now = now,
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
    fun `a recent sync is shown relatively`() {
        setContent(video.copy(lastSyncedAt = NOW - 12 * MINUTE_MS))

        val relative = composeRule.activity.resources
            .getQuantityString(R.plurals.synced_minutes_ago, 12, 12)
        composeRule.onNodeWithText(string(R.string.last_synced).format(relative)).assertIsDisplayed()
    }

    @Test
    fun `a sync older than three hours is shown as an exact stamp`() {
        val syncedAt = NOW - 4 * HOUR_MS
        setContent(video.copy(lastSyncedAt = syncedAt))

        // Formatted in the device's zone, so the expectation is derived rather than hardcoded —
        // what's under test is that the line renders at all and carries the stamp.
        val expected = string(R.string.last_synced).format(formatTimestamp(syncedAt))
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun `a never-synced video shows no sync line`() {
        setContent(video.copy(lastSyncedAt = 0L))

        composeRule.onNodeWithText("Last synced", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a video with no metadata reports why its last fetch failed`() {
        setContent(
            video.copy(
                metadataSource = METADATA_SOURCE_JELLYFIN,
                lastFetchError = "yt-dlp timed out",
                lastFetchErrorAt = 1_784_974_530_000L,
            ),
        )

        composeRule.onNodeWithText("yt-dlp timed out", substring = true).assertIsDisplayed()
    }

    /** An index match since the failed fetch makes the stored error moot, not news. */
    @Test
    fun `a video that since gained metadata hides the stale fetch error`() {
        setContent(
            video.copy(
                metadataSource = METADATA_SOURCE_INDEX,
                lastFetchError = "yt-dlp timed out",
                lastFetchErrorAt = 1_784_974_530_000L,
            ),
        )

        composeRule.onNodeWithText("yt-dlp timed out", substring = true).assertDoesNotExist()
    }

    /** Playback stays available: one missed sync is "probably gone", not "certainly gone". */
    @Test
    fun `a missing video can still be played`() {
        setContent(video.copy(missedSyncs = 1))

        composeRule.onNodeWithText(string(R.string.play)).assertIsEnabled()
    }

    @Test
    fun `the chevron opens the mode menu with all three modes`() {
        setContent(video)

        composeRule.onNodeWithContentDescription(string(R.string.playback_options)).performClick()

        composeRule.onNodeWithText(string(R.string.playback_mode_play)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.playback_mode_external)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.playback_mode_web)).assertIsDisplayed()
    }

    /** Menu item tap = save + act immediately, and with the tapped mode, not the saved one. */
    @Test
    fun `picking a mode from the menu saves it and plays with it`() {
        var saved: PlaybackMode? = null
        var played: PlaybackMode? = null
        setContent(
            video,
            onPlay = { _, _, mode -> played = mode },
            onSelectMode = { saved = it },
        )

        composeRule.onNodeWithContentDescription(string(R.string.playback_options)).performClick()
        composeRule.onNodeWithText(string(R.string.playback_mode_external)).performClick()

        assertEquals(PlaybackMode.EXTERNAL, saved)
        assertEquals(PlaybackMode.EXTERNAL, played)
    }

    @Test
    fun `the main half plays with the saved mode`() {
        var played: PlaybackMode? = null
        setContent(video, onPlay = { _, _, mode -> played = mode })

        composeRule.onNodeWithText(string(R.string.play)).performClick()

        assertEquals(settings.playbackMode, played)
    }

    /** The dropdown's web mode replaced the standalone button (string resource removed too). */
    @Test
    fun `the standalone open in jellyfin button is gone`() {
        setContent(video)

        composeRule.onNodeWithText("Open in Jellyfin").assertDoesNotExist()
    }

    // --- demo mode: one bundled clip, in-app only ---

    private val demoVideo = video.copy(jellyfinItemId = DEMO_ITEM_ID)
    private val demoSettings = Settings(
        serverUrl = "",
        apiKey = "",
        accessToken = "",
        userId = "",
        userName = "",
        libraryId = "",
        libraryName = "",
        indexUrl = "",
        lastSyncAt = 1L,
        lastSyncLibraryId = "",
        demoMode = true,
    )

    /**
     * External hands a URL to another app and Web opens a browser at the server — neither exists
     * in a demo, and no other app can read this one's assets. So the split button loses its half.
     */
    @Test
    fun `demo mode drops the playback mode menu`() {
        setContent(demoVideo, settings = demoSettings)

        composeRule.onNodeWithContentDescription(string(R.string.playback_options)).assertDoesNotExist()
    }

    @Test
    fun `demo mode still plays in app`() {
        var played: PlaybackMode? = null
        setContent(demoVideo, settings = demoSettings, onPlay = { _, _, mode -> played = mode })

        composeRule.onNodeWithText(string(R.string.play)).assertIsEnabled().performClick()

        assertEquals(PlaybackMode.PLAY, played)
    }

    /** A mode saved during an earlier real connection must not survive into the demo. */
    @Test
    fun `demo mode forces play even with another mode saved`() {
        var played: PlaybackMode? = null
        setContent(
            demoVideo,
            settings = demoSettings.copy(playbackMode = PlaybackMode.EXTERNAL),
            onPlay = { _, _, mode -> played = mode },
        )

        composeRule.onNodeWithText(string(R.string.play)).performClick()

        assertEquals(PlaybackMode.PLAY, played)
    }

    private companion object {
        /** A fixed clock for the relative sync labels; ages below are measured back from it. */
        const val NOW = 1_784_974_530_000L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
