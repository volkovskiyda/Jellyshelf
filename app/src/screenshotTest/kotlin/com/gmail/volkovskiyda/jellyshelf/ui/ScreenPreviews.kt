package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_LAST_PLAYED
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_MISSING
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.model.VideoScaleMode
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesContent
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryList
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryVideosContent
import com.gmail.volkovskiyda.jellyshelf.ui.categories.RemoveKind
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailContent
import com.gmail.volkovskiyda.jellyshelf.ui.detail.VideoDetailState
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryContent
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryVideos
import com.gmail.volkovskiyda.jellyshelf.ui.player.FakePlayer
import com.gmail.volkovskiyda.jellyshelf.ui.player.GestureIndicator
import com.gmail.volkovskiyda.jellyshelf.ui.player.GestureIndicatorPill
import com.gmail.volkovskiyda.jellyshelf.ui.player.PlayerControls
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsActions
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsContent
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsUiState
import com.gmail.volkovskiyda.jellyshelf.ui.settings.StatusLine

// Screen-level goldens. Each screen is captured in the states whose layout differs — loading,
// empty, populated — rather than at every window size: these are regression guards for states
// that are awkward to reach by hand, not a responsive-layout matrix.

private const val PHONE_WIDTH = 400
private const val PHONE_HEIGHT = 800

/**
 * The same phone on its side. 800 dp wide is past Material's 600 dp medium breakpoint, so this is
 * the narrowest window that gets the rail and the two-pane detail — and the shortest, which is
 * what makes it the one to watch: whatever does not fit here is below the fold on a real phone.
 */
private const val LANDSCAPE_WIDTH = PHONE_HEIGHT
private const val LANDSCAPE_HEIGHT = PHONE_WIDTH

/**
 * A Pixel Tablet in landscape, in dp: the README's tablet screenshots are shot at this size. Turned
 * upright it is still 800 dp wide — past the medium breakpoint — so it keeps the rail and the two
 * panes, which the portrait goldens below are there to show.
 */
private const val TABLET_WIDTH = 1280
private const val TABLET_HEIGHT = 800

/**
 * Tall enough to hold the whole Updates section and the version line under it, which sit at the
 * foot of the settings list.
 */
private const val SETTINGS_UPDATES_HEIGHT = 1060

/**
 * A pinned build version for the settings footer. Real in shape — `"1.0.<buildNumber>"` is what CI
 * stamps — and fixed, so the goldens don't move every time the commit count does.
 */
private const val PREVIEW_VERSION_NAME = "1.0.294"

/**
 * A pinned clock for the sync-time labels, which are relative for their first three hours. Read
 * from the wall clock these goldens would render differently depending on when they ran; twelve
 * minutes apart, they render "synced 12 minutes ago" every time.
 */
private const val PREVIEW_SYNCED_AT = 1_785_143_919_405L
private const val PREVIEW_NOW = PREVIEW_SYNCED_AT + 12 * 60_000L

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
            totalCount = 0,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
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
            videosOrNull = LibraryVideos(emptyList(), ""),
            query = "",
            totalCount = 0,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
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
            videosOrNull = LibraryVideos(librarySample, ""),
            query = "",
            totalCount = librarySample.size,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
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
            videosOrNull = LibraryVideos(emptyList(), "mafia"),
            query = "mafia",
            totalCount = 879,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

/**
 * Selection mode: the tinted bar with its count and three controls, tinted rows with a checkbox
 * apiece, and the progress strip a run reports through. Every visual difference the mode makes,
 * in one frame — the behavior tests drive the mode but render nothing a golden can compare.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibrarySelecting() {
    PreviewTheme {
        LibraryContent(
            videosOrNull = LibraryVideos(librarySample, ""),
            query = "",
            totalCount = librarySample.size,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
            selectionActive = true,
            selectedIds = librarySample.take(2).map { it.youtubeId }.toSet(),
            selectionRun = SelectionRun(SelectionAction.MARK_WATCHED, BulkProgress.Running(1, 2, 0)),
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun LibrarySelectingDark() {
    PreviewTheme(darkTheme = true) {
        LibraryContent(
            videosOrNull = LibraryVideos(librarySample, ""),
            query = "",
            totalCount = librarySample.size,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
            selectionActive = true,
            selectedIds = librarySample.take(2).map { it.youtubeId }.toSet(),
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
            videosOrNull = LibraryVideos(librarySample, ""),
            query = "",
            totalCount = librarySample.size,
            onQueryChange = {},
            onPlayVideo = {},
            onOpenDetails = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

/**
 * The Library as a wide window shows it: the tabs down the start edge as a rail rather than along
 * the bottom. The nav host draws the rail over the screen and pads the screen clear of it, which is
 * what this `Row` renders to the pixel; the difference is only in how a transition is handled.
 *
 * On a `Surface` because the nav host's `Scaffold` paints the window's background in the app and
 * `LibraryContent` paints none of its own — without one the dark golden is a dark rail and top bar
 * over the preview's white, which is not a layout the app can show.
 */
@Composable
private fun LibraryWithRail() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row {
            TopLevelNavigationRail(selected = AppNavKey.Library, enabled = true, onSelect = {})
            LibraryContent(
                videosOrNull = LibraryVideos(librarySample, ""),
                query = "",
                totalCount = librarySample.size,
                onQueryChange = {},
                onPlayVideo = {},
                onOpenDetails = {},
                scrollStore = FakeScrollPositionRepository(),
                thumbnailModel = { null },
            )
        }
    }
}

/** A phone on its side: the rail's three items and the list beside it, in 400 dp of height. */
@PreviewTest
@Preview(widthDp = LANDSCAPE_WIDTH, heightDp = LANDSCAPE_HEIGHT, showBackground = true)
@Composable
private fun LibraryRailLandscape() {
    PreviewTheme { LibraryWithRail() }
}

@PreviewTest
@Preview(widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT, showBackground = true)
@Composable
private fun LibraryRailTablet() {
    PreviewTheme { LibraryWithRail() }
}

@PreviewTest
@Preview(widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT, showBackground = true)
@Composable
private fun LibraryRailTabletDark() {
    PreviewTheme(darkTheme = true) { LibraryWithRail() }
}

/** The tablet upright: 800 dp is still a medium window, so the tabs stay in the rail. */
@PreviewTest
@Preview(widthDp = TABLET_HEIGHT, heightDp = TABLET_WIDTH, showBackground = true)
@Composable
private fun LibraryRailTabletPortrait() {
    PreviewTheme { LibraryWithRail() }
}

/**
 * The compact form of the same tabs, on its own: the nav host that composes it is not previewable,
 * so this is the one golden the bottom bar has. Categories selected, so the golden shows the
 * indicator on an item that is not the first.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, showBackground = true)
@Composable
private fun TopLevelTabsBar() {
    PreviewTheme {
        TopLevelNavigationBar(selected = AppNavKey.Categories, enabled = true, onSelect = {})
    }
}

@PreviewTest
@Preview(heightDp = LANDSCAPE_HEIGHT, showBackground = true)
@Composable
private fun TopLevelTabsRail() {
    PreviewTheme {
        TopLevelNavigationRail(selected = AppNavKey.Categories, enabled = true, onSelect = {})
    }
}

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
            categories = sampleVideoCategories,
            onOpenCategory = {},
            onBack = {},
            onPlay = { _, _, _ -> },
            onSelectMode = {},
            onToggleWatched = {},
            onFetchMetadata = {},
            onRemove = {},
        )
    }
}

/** [DetailLoaded]'s state, rendered for every wide golden below. */
@Composable
private fun DetailLoadedContent(video: Video = sampleVideo, categories: List<Category> = sampleVideoCategories) {
    DetailContent(
        videoState = VideoDetailState.Loaded(video),
        settings = previewSettings,
        fetching = false,
        thumbnailModel = null,
        categories = categories,
        onOpenCategory = {},
        onBack = {},
        onPlay = { _, _, _ -> },
        onSelectMode = {},
        onToggleWatched = {},
        onFetchMetadata = {},
        onRemove = {},
    )
}

/**
 * The two-pane detail on a phone held sideways: the cover and the "Appears in" chips in the start
 * pane, the title, provenance, actions and description beside them. At 400 dp of height this is
 * the layout that used to be a screenful of thumbnail and nothing else.
 */
@PreviewTest
@Preview(widthDp = LANDSCAPE_WIDTH, heightDp = LANDSCAPE_HEIGHT, showBackground = true)
@Composable
private fun DetailLoadedLandscape() {
    PreviewTheme { DetailLoadedContent() }
}

@PreviewTest
@Preview(widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT, showBackground = true)
@Composable
private fun DetailLoadedTablet() {
    PreviewTheme { DetailLoadedContent() }
}

@PreviewTest
@Preview(widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT, showBackground = true)
@Composable
private fun DetailLoadedTabletDark() {
    PreviewTheme(darkTheme = true) { DetailLoadedContent() }
}

/** The two panes on the tablet upright, where the cover pane is 300 dp wide. */
@PreviewTest
@Preview(widthDp = TABLET_HEIGHT, heightDp = TABLET_WIDTH, showBackground = true)
@Composable
private fun DetailLoadedTabletPortrait() {
    PreviewTheme { DetailLoadedContent() }
}

/**
 * The wide layout's one extra row: Remove keeps a full-width line of its own under the flowing
 * actions, so the destructive button never lines up beside Play.
 */
@PreviewTest
@Preview(widthDp = TABLET_WIDTH, heightDp = TABLET_HEIGHT, showBackground = true)
@Composable
private fun DetailMissingFromServerTablet() {
    PreviewTheme { DetailLoadedContent(video = missingVideo, categories = emptyList()) }
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
            // Empty on purpose: these two goldens guard the missing-from-server state, and the
            // populated section is already covered by DetailLoaded.
            categories = emptyList(),
            onOpenCategory = {},
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
            // Empty on purpose: these two goldens guard the missing-from-server state, and the
            // populated section is already covered by DetailLoaded.
            categories = emptyList(),
            onOpenCategory = {},
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
 * tick markers on the seek bar, and the chapter-step row above it. Mid-queue, so both transport
 * arrows are live. Black background standing in for the video surface the overlay normally
 * covers.
 */
@PreviewTest
@Preview(widthDp = PHONE_HEIGHT, heightDp = PHONE_WIDTH, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerControlsWithChapters() {
    PreviewTheme(darkTheme = true) {
        PlayerControls(
            player = remember { FakePlayer(durationMs = 754_000L, positionMs = 200_000L) },
            visible = true,
            title = "A reasonably long video title that wraps onto a second line",
            positionMs = 200_000L,
            durationMs = 754_000L,
            chapters = listOf(
                Chapter(0L, "Intro"),
                Chapter(120_000L, "Main part"),
                Chapter(600_000L, "Outro"),
            ),
            speed = 1f,
            hasPrevious = true,
            hasNext = true,
            onPrevious = {},
            onNext = {},
            onSeek = {},
            onSetSpeed = {},
            rotateFirst = false,
            scaleMode = VideoScaleMode.FIT,
            onCycleScaleMode = {},
            onRotateToLandscape = {},
            onScrubbingChanged = {},
            speedMenuOpen = false,
            onSpeedMenuChanged = {},
            onOpenChapters = {},
            onBack = {},
            onMinimize = {},
            onEnterPip = {},
        )
    }
}

/**
 * The same overlay at the end of a single-video queue — the notification-reopen case: both
 * transport arrows dimmed, and no chapter-step row at all because the video has no chapters.
 */
@PreviewTest
@Preview(widthDp = PHONE_HEIGHT, heightDp = PHONE_WIDTH, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerControlsSingleVideo() {
    PreviewTheme(darkTheme = true) {
        PlayerControls(
            player = remember { FakePlayer(durationMs = 754_000L, positionMs = 200_000L) },
            visible = true,
            title = "The only video in the queue",
            positionMs = 200_000L,
            durationMs = 754_000L,
            chapters = emptyList(),
            speed = 1f,
            hasPrevious = false,
            hasNext = false,
            onPrevious = {},
            onNext = {},
            onSeek = {},
            onSetSpeed = {},
            rotateFirst = false,
            scaleMode = VideoScaleMode.FIT,
            onCycleScaleMode = {},
            onRotateToLandscape = {},
            onScrubbingChanged = {},
            speedMenuOpen = false,
            onSpeedMenuChanged = {},
            onOpenChapters = {},
            onBack = {},
            onMinimize = {},
            onEnterPip = {},
        )
    }
}

/**
 * The same overlay in portrait, where the last slot in the top bar is the rotate button rather than
 * the scale cycle — the only visual difference, and one no landscape preview can show.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerControlsPortrait() {
    PreviewTheme(darkTheme = true) {
        PlayerControls(
            player = remember { FakePlayer(durationMs = 754_000L, positionMs = 200_000L) },
            visible = true,
            title = "The only video in the queue",
            positionMs = 200_000L,
            durationMs = 754_000L,
            chapters = emptyList(),
            speed = 1f,
            hasPrevious = false,
            hasNext = false,
            onPrevious = {},
            onNext = {},
            onSeek = {},
            onSetSpeed = {},
            rotateFirst = true,
            scaleMode = VideoScaleMode.FIT,
            onCycleScaleMode = {},
            onRotateToLandscape = {},
            onScrubbingChanged = {},
            speedMenuOpen = false,
            onSpeedMenuChanged = {},
            onOpenChapters = {},
            onBack = {},
            onMinimize = {},
            onEnterPip = {},
        )
    }
}

/** The scale button's confirmation pill, naming the mode a tap just moved to. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ScaleModePill() {
    PreviewTheme(darkTheme = true) {
        GestureIndicatorPill(GestureIndicator.ScaleMode(VideoScaleMode.ZOOM))
    }
}

/** The swipe-to-seek pill: where the finger would land, and the signed distance jumped. */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SeekGesturePill() {
    PreviewTheme(darkTheme = true) {
        GestureIndicatorPill(GestureIndicator.Seek(targetMs = 754_000L, deltaMs = 45_000L))
    }
}

/**
 * The press-and-hold pill at a speed only a swipe can reach — the half of the ladder the speed
 * menu does not offer, so a golden here is the only thing watching how it renders.
 */
@PreviewTest
@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SpeedGesturePill() {
    PreviewTheme(darkTheme = true) {
        GestureIndicatorPill(GestureIndicator.Speed(speed = 5f))
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
            categories = emptyList(),
            onOpenCategory = {},
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

// --- The "Missing from server" filter (Others tab) and its local-only bulk removal ---

/**
 * The three videos the sync has stopped seeing on the server, and the one action the filter adds.
 *
 * Three things this state and no other shows, which is what makes it worth a golden: the removal
 * button is red (it is destructive, even though it destroys nothing on the server), every row
 * carries the missing-from-server notice, and the top bar has *no* playlist button — a playlist
 * is built from Jellyfin item ids and these are exactly the ids the server no longer has.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoryVideosMissingFromServer() {
    PreviewTheme { MissingFromServerPreview() }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoryVideosMissingFromServerDark() {
    PreviewTheme(darkTheme = true) { MissingFromServerPreview() }
}

/**
 * The confirmation, which is the one screen in the app where a wrong word costs real data: its
 * sibling on the Watched filter deletes the media off the Jellyfin server irrecoverably, and this
 * one has to be unmistakably the other thing.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoryVideosMissingConfirm() {
    PreviewTheme { MissingFromServerPreview(showRemoveDialog = true) }
}

/** Mid-run: the shared bulk header's progress bar, count and Cancel. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoryVideosMissingRemoving() {
    PreviewTheme {
        MissingFromServerPreview(bulkRemove = BulkProgress.Running(done = 1, total = 3, failed = 0))
    }
}

/**
 * Where the run leaves you: the summary with its Dismiss, over the filter's own empty state. Worth
 * pinning because it is the one empty state reached by emptying the list from this very screen —
 * the Others tab hides a filter whose count is zero, so it is never *entered* empty.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoryVideosMissingRemoved() {
    PreviewTheme {
        MissingFromServerPreview(
            videos = emptyList(),
            bulkRemove = BulkProgress.Done(total = 3, failed = 0),
        )
    }
}

/** The Others tab with every virtual filter, including Last played in its slot after Continue. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoriesOthersWithMissing() {
    PreviewTheme {
        CategoriesContent(
            categoriesOrNull = CategoryList(emptyList(), pristine = true),
            others = sampleOthers,
            query = "",
            searchAll = false,
            selectedType = CATEGORY_TYPE_OTHERS,
            selectionLoaded = true,
            onQueryChange = {},
            onSearchAllChange = {},
            onSelectedTypeChange = {},
            onCategoryClick = { _, _ -> },
            scrollStore = FakeScrollPositionRepository(),
        )
    }
}

/** Every virtual filter, in the order `observeOthers` emits them — the tab renders it verbatim. */
private val sampleOthers = listOf(
    category("virtual:uncategorized", "Uncategorized", CATEGORY_TYPE_OTHERS, 4),
    category("virtual:continue", "Continue watching", CATEGORY_TYPE_OTHERS, 6),
    category(VIRTUAL_CATEGORY_LAST_PLAYED, "Last played", CATEGORY_TYPE_OTHERS, 5),
    category("virtual:unwatched", "Unwatched", CATEGORY_TYPE_OTHERS, 45),
    category("virtual:watched", "Watched", CATEGORY_TYPE_OTHERS, 15),
    category(VIRTUAL_CATEGORY_MISSING, "Missing from server", CATEGORY_TYPE_OTHERS, 3),
)

private val missingSample = listOf(
    missingVideo,
    missingVideo.copy(youtubeId = "b", title = "Second video the server dropped"),
    missingVideo.copy(youtubeId = "c", title = "Third video the server dropped"),
)

/**
 * Every state above differs only in the bulk-run state and whether the list still has rows, so
 * they share one call rather than five near-identical ones — a golden that drifts because a
 * preview was edited and its siblings weren't proves nothing.
 */
@Composable
private fun MissingFromServerPreview(
    videos: List<Video> = missingSample,
    bulkRemove: BulkProgress = BulkProgress.Idle,
    showRemoveDialog: Boolean = false,
) {
    CategoryVideosContent(
        title = "Missing from server",
        videosOrNull = videos,
        bulkFetch = BulkProgress.Idle,
        bulkRemove = bulkRemove,
        demoMode = false,
        isUncategorized = false,
        removeKind = RemoveKind.MISSING,
        scrollKey = "preview.missing",
        showRemoveDialog = showRemoveDialog,
        onShowRemoveDialog = {},
        onDismissRemoveDialog = {},
        onPlayVideo = {},
        onOpenDetails = {},
        onBack = {},
        onStartFetchMissing = {},
        onCancelFetchMissing = {},
        onAcknowledgeBulkFetch = {},
        onConfirmRemove = {},
        onCancelRemove = {},
        onAcknowledgeBulkRemove = {},
        scrollStore = FakeScrollPositionRepository(),
        thumbnailModel = { null },
    )
}

/**
 * The "Last played" filter: recency order, not file-name order — the watched video below sits
 * *under* the part-watched one because it was played earlier, which no other list in the app can
 * show. Mixed states on purpose: any play counts, finished or not.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun CategoryVideosLastPlayed() {
    PreviewTheme {
        CategoryVideosContent(
            title = "Last played",
            videosOrNull = lastPlayedSample,
            bulkFetch = BulkProgress.Idle,
            bulkRemove = BulkProgress.Idle,
            demoMode = false,
            isUncategorized = false,
            removeKind = null,
            scrollKey = "preview.lastplayed",
            showRemoveDialog = false,
            onShowRemoveDialog = {},
            onDismissRemoveDialog = {},
            onPlayVideo = {},
            onOpenDetails = {},
            onBack = {},
            onStartFetchMissing = {},
            onCancelFetchMissing = {},
            onAcknowledgeBulkFetch = {},
            onConfirmRemove = {},
            onCancelRemove = {},
            onAcknowledgeBulkRemove = {},
            scrollStore = FakeScrollPositionRepository(),
            thumbnailModel = { null },
        )
    }
}

/** Newest play first; the titles say the order out loud so a drifted golden reads as wrong. */
private val lastPlayedSample = listOf(
    partWatchedVideo.copy(youtubeId = "lp1", title = "Stopped partway this morning"),
    watchedVideo.copy(youtubeId = "lp2", title = "Finished yesterday evening"),
    watchedVideo.copy(youtubeId = "lp3", title = "Finished last week"),
)

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

/**
 * A running demo: the "Try demo" button has given way to the caption that says what the library is
 * and warns that connecting will replace it. The state is otherwise a disconnected install, which
 * is exactly what a demo is.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun SettingsDemoMode() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(demoMode = true, lastSyncAt = PREVIEW_SYNCED_AT),
            videoCount = 60,
            actions = SettingsActions(),
            now = PREVIEW_NOW,
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
                apiKeyConnected = true,
                syncStatus = StatusLine("Synced 812/879 videos with metadata into 214 categories"),
                lastSyncAt = PREVIEW_SYNCED_AT,
            ),
            videoCount = 879,
            actions = SettingsActions(),
            now = PREVIEW_NOW,
        )
    }
}

/**
 * The API-key fallback (Alternative sign in) and the Advanced section, both expanded. Its own
 * preview because both sections are collapsed by default, and paths that only appear behind a tap
 * would otherwise have no golden at all.
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
                // The API-key path is never `signedIn`, and this is what still offers it a way
                // out — the only golden that renders Sign out beside a Sign in button.
                apiKeyConnected = true,
            ),
            videoCount = 879,
            actions = SettingsActions(),
            advancedExpanded = true,
            alternativeSignInExpanded = true,
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
                lastSyncAt = PREVIEW_SYNCED_AT,
            ),
            videoCount = 879,
            actions = SettingsActions(),
            advancedExpanded = true,
            now = PREVIEW_NOW,
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
                apiKeyConnected = true,
                syncStatus = StatusLine(
                    "Synced 0/879 videos with metadata into 0 categories • metadata index unavailable",
                ),
                lastSyncAt = PREVIEW_SYNCED_AT,
            ),
            videoCount = 879,
            actions = SettingsActions(),
            now = PREVIEW_NOW,
        )
    }
}

/**
 * The three update-channel states, each tall enough to hold the whole Updates section — at
 * [PHONE_HEIGHT] the "Check now" button and the last-checked line fall below the fold, which is
 * also why only four of the six [SettingsContent] goldens changed when the section landed.
 *
 * All three rely on `SettingsUiState.isDebugBuild` defaulting to **false**. Screenshot tests build
 * the debug variant, so if that default ever flips these render an empty gap and the goldens
 * silently stop covering the feature.
 */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = SETTINGS_UPDATES_HEIGHT, showBackground = true)
@Composable
private fun SettingsUpdatesOff() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                updateSource = UpdateSource.NONE,
                versionName = PREVIEW_VERSION_NAME,
            ),
            videoCount = 0,
            actions = SettingsActions(),
            now = PREVIEW_NOW,
        )
    }
}

@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = SETTINGS_UPDATES_HEIGHT, showBackground = true)
@Composable
private fun SettingsUpdatesGitHub() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                updateSource = UpdateSource.GITHUB,
                lastUpdateCheckAt = PREVIEW_SYNCED_AT,
                versionName = PREVIEW_VERSION_NAME,
            ),
            videoCount = 0,
            actions = SettingsActions(),
            now = PREVIEW_NOW,
        )
    }
}

/** The tester channel, reporting the failure a restricted API key actually produces. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = SETTINGS_UPDATES_HEIGHT, showBackground = true)
@Composable
private fun SettingsUpdatesAppDistribution() {
    PreviewTheme {
        SettingsContent(
            state = SettingsUiState(
                updateSource = UpdateSource.APP_DISTRIBUTION,
                lastUpdateCheckAt = PREVIEW_SYNCED_AT,
                updateError = UpdateCheckError.ApiDisabled,
                versionName = PREVIEW_VERSION_NAME,
            ),
            videoCount = 0,
            actions = SettingsActions(),
            now = PREVIEW_NOW,
        )
    }
}

/** The offer itself: a version, and notes long enough to show they scroll rather than push the buttons off. */
@PreviewTest
@Preview(widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Composable
private fun UpdateDialogPreview() {
    PreviewTheme {
        UpdateDialog(
            info = UpdateInfo(
                versionCode = 181,
                versionName = "1.1",
                releaseNotes = "• Playback speed is remembered across launches\n" +
                    "• Faster library browsing on large collections\n" +
                    "• The player no longer sticks at 3× when the screen leaves mid-hold\n" +
                    "• Watched markers now match the server's 90% threshold\n" +
                    "\n**Full Changelog**: https://github.com/volkovskiyda/Jellyshelf/commits/v1.1",
                downloadUrl = "https://github.com/volkovskiyda/Jellyshelf/releases/download/" +
                    "v1.1/jellyshelf-1.1.181.apk",
                source = UpdateSource.GITHUB,
            ),
            onUpdate = {},
            onDismiss = {},
        )
    }
}
