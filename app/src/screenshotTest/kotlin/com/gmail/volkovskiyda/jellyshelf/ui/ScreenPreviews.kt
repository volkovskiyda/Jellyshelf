package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesContent
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryList
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailContent
import com.gmail.volkovskiyda.jellyshelf.ui.detail.VideoDetailState
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryContent
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryVideos
import com.gmail.volkovskiyda.jellyshelf.ui.player.GestureIndicator
import com.gmail.volkovskiyda.jellyshelf.ui.player.GestureIndicatorPill
import com.gmail.volkovskiyda.jellyshelf.ui.player.IndicatorControl
import com.gmail.volkovskiyda.jellyshelf.ui.player.PlayerControls
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsActions
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsContent
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsUiState

// Screen-level goldens. Each screen is captured in the states whose layout differs — loading,
// empty, populated — rather than at every window size: these are regression guards for states
// that are awkward to reach by hand, not a responsive-layout matrix.

private const val PHONE_WIDTH = 400
private const val PHONE_HEIGHT = 800

private val librarySample = listOf(
    sampleVideo,
    watchedVideo.copy(youtubeId = "b", title = "Second video, already watched"),
    partWatchedVideo.copy(youtubeId = "c", title = "Third video, partly watched"),
    missingVideo.copy(youtubeId = "d", title = "Fourth video, gone from the server"),
)

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibraryLoading() {
    PreviewTheme {
        LibraryContent(
            videosOrNull = null,
            query = "",
            durationFilter = null,
            totalCount = 0,
            onQueryChange = {},
            onDurationFilterChange = {},
            onVideoClick = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibraryEmpty() {
    PreviewTheme {
        LibraryContent(
            videosOrNull = LibraryVideos(emptyList(), "", null),
            query = "",
            durationFilter = null,
            totalCount = 0,
            onQueryChange = {},
            onDurationFilterChange = {},
            onVideoClick = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibraryPopulated() {
    PreviewTheme {
        LibraryContent(
            videosOrNull = LibraryVideos(librarySample, "", null),
            query = "",
            durationFilter = null,
            totalCount = librarySample.size,
            onQueryChange = {},
            onDurationFilterChange = {},
            onVideoClick = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

/** Search matched nothing: the count reads shown/total and the message names the query (item 05). */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibraryNoMatch() {
    PreviewTheme {
        LibraryContent(
            videosOrNull = LibraryVideos(emptyList(), "mafia", DurationBucket.entries.first()),
            query = "mafia",
            durationFilter = DurationBucket.entries.first(),
            totalCount = 879,
            onQueryChange = {},
            onDurationFilterChange = {},
            onVideoClick = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibraryPopulatedDark() {
    PreviewTheme(darkTheme = true) {
        LibraryContent(
            videosOrNull = LibraryVideos(librarySample, "", null),
            query = "",
            durationFilter = null,
            totalCount = librarySample.size,
            onQueryChange = {},
            onDurationFilterChange = {},
            onVideoClick = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

private val previewSettings = Settings(
    serverUrl = "https://jellyfin.example.org",
    apiKey = "00000000000000000000000000000000",
    accessToken = "",
    userId = "user-id",
    userName = "Sample User",
    libraryId = "",
    libraryName = "",
    indexUrl = "https://jellyfin.example.org/jellyshelf-index.json",
    lastSyncAt = 0L,
    lastSyncLibraryId = "",
)

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun DetailLoaded() {
    PreviewTheme {
        DetailContent(
            videoState = VideoDetailState.Loaded(sampleVideo),
            settings = previewSettings,
            fetching = false,
            thumbnailModel = null,
            onBack = {},
            onPlay = { _, _, _ -> },
            onSelectMode = {},
            onToggleWatched = {},
            onFetchMetadata = {},
            onRemove = {},
        )
    }
}

/** The missing-from-server notice plus the Remove action, which only this state shows. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun DetailMissingFromServer() {
    PreviewTheme {
        DetailContent(
            videoState = VideoDetailState.Loaded(missingVideo),
            settings = previewSettings,
            fetching = false,
            thumbnailModel = null,
            onBack = {},
            onPlay = { _, _, _ -> },
            onSelectMode = {},
            onToggleWatched = {},
            onFetchMetadata = {},
            onRemove = {},
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun DetailMissingFromServerDark() {
    PreviewTheme(darkTheme = true) {
        DetailContent(
            videoState = VideoDetailState.Loaded(missingVideo),
            settings = previewSettings,
            fetching = false,
            thumbnailModel = null,
            onBack = {},
            onPlay = { _, _, _ -> },
            onSelectMode = {},
            onToggleWatched = {},
            onFetchMetadata = {},
            onRemove = {},
        )
    }
}

/**
 * The player's controls overlay with chapters, landscape: the chapters button in the top bar,
 * tick markers on the seek bar, and the current chapter's title above it. Black background
 * standing in for the video surface the overlay normally covers.
 */
@PreviewTest
@Preview(widthDp = PHONE_HEIGHT, heightDp = PHONE_WIDTH, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerControlsWithChapters() {
    PreviewTheme(darkTheme = true) {
        PlayerControls(
            title = "A reasonably long video title that wraps onto a second line",
            showPlay = false,
            positionMs = 200_000L,
            durationMs = 754_000L,
            chapters = listOf(
                Chapter(0L, "Intro"),
                Chapter(120_000L, "Main part"),
                Chapter(600_000L, "Outro"),
            ),
            speed = 1f,
            onPlayPause = {},
            onSeekBack = {},
            onSeekForward = {},
            onSeek = {},
            onSetSpeed = {},
            onScrubbingChanged = {},
            onSpeedMenuChanged = {},
            onOpenChapters = {},
            onBack = {},
        )
    }
}

/** The volume drag's feedback pill, mid-gesture, over the black player surface. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VolumeGesturePill() {
    PreviewTheme(darkTheme = true) {
        GestureIndicatorPill(GestureIndicator(IndicatorControl.VOLUME, 0.64f))
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun DetailNotFound() {
    PreviewTheme {
        DetailContent(
            videoState = VideoDetailState.NotFound,
            settings = previewSettings,
            fetching = false,
            thumbnailModel = null,
            onBack = {},
            onPlay = { _, _, _ -> },
            onSelectMode = {},
            onToggleWatched = {},
            onFetchMetadata = {},
            onRemove = {},
        )
    }
}

private fun category(id: String, name: String, type: String, count: Int) = CategoryWithCount(
    category = Category(id = id, name = name, type = type, createdAt = 0L),
    videoCount = count,
)

private val sampleCategories = listOf(
    category("channel:a", "Sample Channel", CATEGORY_TYPE_AUTO_CHANNEL, 42),
    category("channel:b", "Another Channel", CATEGORY_TYPE_AUTO_CHANNEL, 7),
    category("year:2026", "2026", CATEGORY_TYPE_AUTO_YEAR, 120),
)

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoriesTabs() {
    PreviewTheme {
        CategoriesContent(
            categoriesOrNull = CategoryList(sampleCategories, pristine = true),
            others = emptyList(),
            query = "",
            searchAll = false,
            selectedType = CATEGORY_TYPE_AUTO_CHANNEL,
            selectionLoaded = true,
            onQueryChange = {},
            onSearchAllChange = {},
            onSelectedTypeChange = {},
            onCategoryClick = { _, _ -> },
            scrollStore = FakeScrollPositionRepository(),
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoriesSearchAcrossDimensions() {
    PreviewTheme {
        CategoriesContent(
            categoriesOrNull = CategoryList(sampleCategories, pristine = false),
            others = emptyList(),
            query = "chan",
            searchAll = true,
            selectedType = CATEGORY_TYPE_AUTO_CHANNEL,
            selectionLoaded = true,
            onQueryChange = {},
            onSearchAllChange = {},
            onSelectedTypeChange = {},
            onCategoryClick = { _, _ -> },
            scrollStore = FakeScrollPositionRepository(),
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoriesEmpty() {
    PreviewTheme {
        CategoriesContent(
            categoriesOrNull = CategoryList(emptyList(), pristine = true),
            others = emptyList(),
            query = "",
            searchAll = false,
            selectedType = null,
            selectionLoaded = true,
            onQueryChange = {},
            onSearchAllChange = {},
            onSelectedTypeChange = {},
            onCategoryClick = { _, _ -> },
            scrollStore = FakeScrollPositionRepository(),
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun SettingsDisconnected() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(),
            videoCount = 0,
            actions = SettingsActions(),
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun SettingsConnected() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                serverUrl = previewSettings.serverUrl,
                apiKey = previewSettings.apiKey,
                indexUrl = previewSettings.indexUrl,
                users = listOf(
                    com.gmail.volkovskiyda.jellyshelf.domain.model.User("user-id", "Sample User"),
                    com.gmail.volkovskiyda.jellyshelf.domain.model.User("other-id", "Other User"),
                ),
                selectedUserId = "user-id",
                selectedUserName = "Sample User",
                status = "Synced 812/879 videos with metadata into 214 categories",
                lastSyncAt = 1_785_143_919_405L,
            ),
            videoCount = 879,
            actions = SettingsActions(),
        )
    }
}

/**
 * The API-key fallback, expanded. Its own preview because the section is collapsed by default, and
 * a path that only appears behind a tap would otherwise have no golden at all.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun SettingsAdvancedExpanded() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                serverUrl = previewSettings.serverUrl,
                apiKey = previewSettings.apiKey,
                indexUrl = previewSettings.indexUrl,
                users = listOf(
                    com.gmail.volkovskiyda.jellyshelf.domain.model.User("user-id", "Sample User"),
                    com.gmail.volkovskiyda.jellyshelf.domain.model.User("other-id", "Other User"),
                ),
                selectedUserId = "user-id",
                selectedUserName = "Sample User",
            ),
            videoCount = 879,
            actions = SettingsActions(),
            advancedExpanded = true,
        )
    }
}

/** Signed in with a user token: no password field, no API-key affordances, Sign out instead. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun SettingsSignedIn() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                serverUrl = previewSettings.serverUrl,
                indexUrl = previewSettings.indexUrl,
                username = "Sample User",
                signedIn = true,
                selectedUserId = "user-id",
                selectedUserName = "Sample User",
                lastSyncAt = 1_785_143_919_405L,
            ),
            videoCount = 879,
            actions = SettingsActions(),
            advancedExpanded = true,
        )
    }
}

/** Sync succeeded but the metadata index was unreachable — the degraded status line from item 09. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun SettingsIndexUnavailable() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                serverUrl = previewSettings.serverUrl,
                apiKey = previewSettings.apiKey,
                indexUrl = previewSettings.indexUrl,
                selectedUserId = "user-id",
                selectedUserName = "Sample User",
                status = "Synced 0/879 videos with metadata into 0 categories • metadata index unavailable",
                lastSyncAt = 1_785_143_919_405L,
            ),
            videoCount = 879,
            actions = SettingsActions(),
        )
    }
}
