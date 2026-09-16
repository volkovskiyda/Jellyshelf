package com.gmail.volkovskiyda.jellyshelf.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.BackButton
import com.gmail.volkovskiyda.jellyshelf.ui.ClickThrottle
import com.gmail.volkovskiyda.jellyshelf.ui.DestructiveButton
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.ToastOnMessage
import com.gmail.volkovskiyda.jellyshelf.ui.formatSyncTime
import com.gmail.volkovskiyda.jellyshelf.ui.isMediumWidthOrWider
import com.gmail.volkovskiyda.jellyshelf.ui.rememberClickThrottle
import com.gmail.volkovskiyda.jellyshelf.ui.rememberCopyToClipboard
import com.gmail.volkovskiyda.jellyshelf.ui.rememberNow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberVideoThumbnailResolver
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import com.gmail.volkovskiyda.jellyshelf.util.formatUploadDate
import com.gmail.volkovskiyda.jellyshelf.util.ticksToMillis
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import timber.log.Timber

/**
 * Detail screen: binds [DetailViewModel] and the playback-mode dispatch — in-app player push,
 * external-player handoff, web deep link — to the stateless [DetailContent] below, which
 * previews and tests can render on its own. [onPlayInApp] comes from the nav host: pushing the
 * player screen is navigation, and navigation stays in JellyshelfNav like every other push.
 */
@Composable
fun DetailScreen(
    youtubeId: String,
    onBack: () -> Unit,
    onPlayInApp: (String) -> Unit,
    onOpenCategory: (categoryId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: DetailViewModel = koinViewModel { parametersOf(youtubeId) }

    // Launch the external player for a result; MX Player / VLC hand back the final position,
    // which we persist locally and report to Jellyfin as PlaybackStopped.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        Timber.tag(Playback.TAG).d("player returned: resultCode=${result.resultCode} hasData=${result.data != null}")
        val playback = Playback.parseResult(result.data)
            ?: return@rememberLauncherForActivityResult
        Timber.tag(Playback.TAG).d(
            "reporting playback stopped: positionMs=${playback.positionMs} " +
                "completed=${playback.completed}",
        )
        viewModel.reportPlaybackStopped(playback.positionMs, playback.completed)
    }

    val videoState by viewModel.video.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val fetching by viewModel.fetching.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    val message by viewModel.message.collectAsStateWithLifecycle()
    ToastOnMessage(message) { viewModel.consumeMessage() }

    // Leave as soon as this screen's own Remove has landed, instead of showing the user the
    // "video not found" state their tap just created.
    val removed by viewModel.removed.collectAsStateWithLifecycle()
    LaunchedEffect(removed) { if (removed) onBack() }

    val current = (videoState as? VideoDetailState.Loaded)?.video
    // Resolved here rather than inside the content: it reads the live api key out of Koin, which
    // host-side rendering has no container for (same seam as VideoRow's thumbnailModel). The
    // resolver is remembered unconditionally so a video arriving/leaving can't restart its
    // settings subscription.
    val resolveThumbnail = rememberVideoThumbnailResolver()
    val thumbnailModel = current?.let(resolveThumbnail)

    val now by rememberNow()
    DetailContent(
        now = now,
        videoState = videoState,
        settings = settings,
        fetching = fetching,
        thumbnailModel = thumbnailModel,
        categories = categories,
        onOpenCategory = { category -> onOpenCategory(category.id, category.name) },
        onBack = onBack,
        onPlay = { video, s, requestedMode ->
            // The content hides the other modes in demo; this is the same rule at the dispatch,
            // so nothing — a stale saved mode, a future caller — can hand a demo video to another
            // app or to a browser pointed at a server that isn't there.
            val mode = if (s.demoMode) PlaybackMode.PLAY else requestedMode
            when (mode) {
                PlaybackMode.PLAY -> onPlayInApp(video.youtubeId)
                PlaybackMode.EXTERNAL -> playerLauncher.launch(
                    Playback.externalPlayerIntent(
                        context = context,
                        serverUrl = s.serverUrl,
                        itemId = requireNotNull(video.jellyfinItemId),
                        credential = s.credential,
                        title = video.title,
                        resumeMs = ticksToMillis(video.playbackPositionTicks),
                        tokenInQuery = s.tokenInQuery,
                    ),
                )
                PlaybackMode.WEB ->
                    Playback.openInJellyfin(context, s.serverUrl, requireNotNull(video.jellyfinItemId))
            }
        },
        onSelectMode = viewModel::setPlaybackMode,
        onToggleWatched = viewModel::toggleWatched,
        onFetchMetadata = viewModel::fetchMetadata,
        onRemove = viewModel::removeFromLibrary,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // stateless content: one parameter per thing it renders or reports
internal fun DetailContent(
    videoState: VideoDetailState,
    settings: Settings?,
    fetching: Boolean,
    thumbnailModel: String?,
    categories: List<Category>,
    onOpenCategory: (Category) -> Unit,
    onBack: () -> Unit,
    onPlay: (Video, Settings, PlaybackMode) -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
    onToggleWatched: () -> Unit,
    onFetchMetadata: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    // Passed in rather than read here, so previews and tests can pin it: a relative label built
    // from the wall clock would make their output depend on when they ran.
    now: Long = 0L,
) {
    val current = (videoState as? VideoDetailState.Loaded)?.video

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(current?.channel ?: stringResource(R.string.video_fallback_title)) },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (videoState) {
                    VideoDetailState.Loading -> LoadingState()
                    else -> EmptyState(stringResource(R.string.video_not_found))
                }
            }
            return@Scaffold
        }

        // One scrolling column either way, in four stacked sections: a header, the buttons, the
        // "Appears in" chips and the description. Only the header changes shape with the window —
        // from a medium width up (a phone on its side, a tablet either way) the cover sits beside
        // the title and Play rather than above them. That is what keeps the cover from being the
        // whole first screenful: full width on a landscape tablet a 16:9 image is 450 dp tall, and
        // the history of DetailContentTest is the tests learning to scroll past it. Everything
        // under the header is full width in both, so the chips and the description flow across the
        // whole window rather than down a pane of it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val wide = isMediumWidthOrWider()
            Header(
                current = current,
                settings = settings,
                thumbnailModel = thumbnailModel,
                now = now,
                wide = wide,
                onPlay = onPlay,
                onSelectMode = onSelectMode,
            )
            Actions(
                current = current,
                fetching = fetching,
                onToggleWatched = onToggleWatched,
                onFetchMetadata = onFetchMetadata,
                onRemove = onRemove,
            )
            AppearsInSection(categories = categories, onOpenCategory = onOpenCategory)
            DescriptionSection(current)
        }
    }
}

/** The cover's share of a wide header's width; the title, the provenance and Play take the rest. */
private const val COVER_WEIGHT = 2f
private const val HEADING_WEIGHT = 3f
private val HEADER_GAP = 24.dp

/**
 * What the video is and the one control that plays it: the cover, the file name, the provenance
 * and Play.
 *
 * Side by side from a medium width up, stacked on a compact window — the only part of this screen
 * whose shape depends on the window. Stacked, the three pieces are emitted straight into the
 * screen's own column, so its 12 dp spacing separates them exactly as it separates the sections.
 */
@Composable
@Suppress("LongParameterList") // the header's whole state, and the two callbacks Play reports
private fun Header(
    current: Video,
    settings: Settings?,
    thumbnailModel: String?,
    now: Long,
    wide: Boolean,
    onPlay: (Video, Settings, PlaybackMode) -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(HEADER_GAP)) {
            Cover(thumbnailModel, Modifier.weight(COVER_WEIGHT))
            Column(
                modifier = Modifier.weight(HEADING_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Heading(current, now)
                PlayButton(current, settings, onPlay, onSelectMode)
            }
        }
    } else {
        Cover(thumbnailModel)
        Heading(current, now)
        PlayButton(current, settings, onPlay, onSelectMode)
    }
}

/** The thumbnail, as wide as whatever holds it and always 16:9. */
@Composable
private fun Cover(thumbnailModel: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = thumbnailModel,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * What identifies the video and where its metadata stands: the file name, the channel/duration/date
 * line, the provenance line, and — when there is something to say — why the last fetch failed and
 * that the server has stopped listing it.
 */
@Composable
private fun Heading(current: Video, now: Long) {
    // The file name leads, not the YouTube title: it is the on-disk identity the user
    // manages the library by, and a tap puts it on the clipboard.
    val copyFileName = rememberCopyToClipboard(R.string.file_name_copied)
    Text(
        current.fileName,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.copy_file_name),
        ) { copyFileName(current.fileName) },
    )

    val meta = buildList {
        current.channel?.let { add(it) }
        if (current.durationSeconds > 0) add(formatDuration(current.durationSeconds))
        formatUploadDate(current.uploadDate)?.let { add(it) }
    }.joinToString("  •  ")
    if (meta.isNotBlank()) {
        Text(
            meta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Provenance on one line: where the metadata came from, and when this row last saw a
    // sync. Omitted entirely for a never-synced video rather than printing an epoch date.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetadataSourceBadge(current.metadataSource)
        formatSyncTime(current.lastSyncedAt, now)?.let { syncedAt ->
            Text(
                stringResource(R.string.last_synced, syncedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Why this video still has no metadata. Only while it actually lacks any: an index
    // match since the failed fetch makes the stored error moot, not news.
    val fetchError = current.lastFetchError
    if (fetchError != null && current.metadataSource == METADATA_SOURCE_JELLYFIN) {
        Text(
            stringResource(
                R.string.last_fetch_failed,
                formatSyncTime(current.lastFetchErrorAt, now).orEmpty(),
                fetchError,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (current.missingFromServer) MissingFromServerNotice()
}

/**
 * Everything done to a video that is not playing it: the watched toggle, the metadata fetch and —
 * once the server has stopped listing it — removal.
 *
 * A flow rather than a plain row: each button asks for [ACTION_MIN_WIDTH], enough to hold the
 * longest of these labels on one line, and any that does not fit drops to the next line instead of
 * squeezing its label onto three. Whatever ends up sharing a line then splits it equally. On a
 * phone upright that is two buttons and then one; on a tablet all three sit together.
 *
 * Removal rides in the flow with the other two rather than apart from them, so this is the whole
 * of what the screen can do to the video; it keeps the error colour that says which one it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    current: Video,
    fetching: Boolean,
    onToggleWatched: () -> Unit,
    onFetchMetadata: () -> Unit,
    onRemove: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val buttonModifier = Modifier
            .weight(1f)
            .widthIn(min = ACTION_MIN_WIDTH)
            .heightIn(min = ACTION_MIN_HEIGHT)

        OutlinedButton(
            onClick = onToggleWatched,
            modifier = buttonModifier,
            contentPadding = ACTION_CONTENT_PADDING,
        ) {
            Text(
                stringResource(
                    if (current.played) R.string.mark_unwatched else R.string.mark_watched,
                ),
                textAlign = TextAlign.Center,
            )
        }

        // Fetch / refresh YouTube metadata in-app with the bundled yt-dlp. "Get" when the video
        // only has Jellyfin data, "Update" once any feed or fetch supplied metadata.
        val hasMetadata = current.metadataSource != METADATA_SOURCE_JELLYFIN
        OutlinedButton(
            onClick = onFetchMetadata,
            enabled = !fetching,
            modifier = buttonModifier,
            contentPadding = ACTION_CONTENT_PADDING,
        ) {
            Text(
                stringResource(
                    when {
                        fetching -> R.string.fetching_metadata
                        hasMetadata -> R.string.update_metadata
                        else -> R.string.get_metadata
                    },
                ),
                textAlign = TextAlign.Center,
            )
        }

        // Only offered once the server has stopped listing the video: for anything still in
        // the library, sync owns the local rows and a manual delete would just be undone.
        if (current.missingFromServer) {
            DestructiveButton(
                label = stringResource(R.string.remove_from_library),
                onClick = onRemove,
                modifier = buttonModifier,
                contentPadding = ACTION_CONTENT_PADDING,
            )
        }
    }
}

/**
 * One playback control: a split button. The main half acts with the saved mode; the chevron opens
 * the mode menu, and picking an item saves the mode app-wide AND acts with it immediately
 * (split-button convention, like IDE run buttons). Every mode leaves this screen one way or
 * another, and the second tap of a double-tap would land before whatever launched is on top — one
 * shared throttle for the whole row.
 *
 * In demo mode it is a plain button: EXTERNAL hands another app a URL, and WEB opens a server in a
 * browser — neither of which exists here, and no other app can read this one's assets. The mode is
 * forced to PLAY at dispatch as well as hidden, so a mode saved during an earlier real connection
 * cannot fire a broken intent.
 */
@Composable
internal fun PlayButton(
    current: Video,
    settings: Settings?,
    onPlay: (Video, Settings, PlaybackMode) -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val itemId = current.jellyfinItemId
        val s = settings
        val enabled = s != null && itemId != null
        val demo = s?.demoMode == true
        // One value for both what the button says and what it does, so they cannot drift apart.
        val playMode = if (demo) PlaybackMode.PLAY else s?.playbackMode ?: PlaybackMode.PLAY
        val launchThrottle = rememberClickThrottle()
        Button(
            onClick = {
                if (s != null && itemId != null) launchThrottle { onPlay(current, s, playMode) }
            },
            enabled = enabled,
            shape = if (demo) ButtonDefaults.shape else SplitButtonStartShape,
            modifier = Modifier.heightIn(min = PLAY_HEIGHT),
            contentPadding = PLAY_CONTENT_PADDING,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            // Which of the three ways to play a tap will take, under the word Play — the same
            // mode the chevron's menu has ticked, in the shorter wording [sourceLabelRes] carries.
            // Demo mode has only ever one answer and still shows it, rather than leaving a button
            // that is a different shape from the real one.
            Column {
                Text(stringResource(R.string.play))
                Text(
                    stringResource(playMode.sourceLabelRes),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (!demo) {
            PlaybackModeMenu(
                current = current,
                settings = s,
                itemId = itemId,
                enabled = enabled,
                launchThrottle = launchThrottle,
                onPlay = onPlay,
                onSelectMode = onSelectMode,
            )
        }
    }
}

/** The description, selectable, under its own heading — or nothing at all for a video without one. */
@Composable
private fun DescriptionSection(current: Video) {
    current.description?.takeIf { it.isNotBlank() }?.let { desc ->
        Text(stringResource(R.string.description), style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(desc, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The dimensions the "Appears in" section shows, in display order, each with its row label.
 * Auto dimensions only: manual playlists and the virtual Others filters are deliberately absent.
 * All but the last hold exactly one value per video, hence the singular labels; a video can carry
 * several YouTube categories, so that row reuses the Categories tab's plural.
 */
private val APPEARS_IN_DIMENSIONS = listOf(
    CATEGORY_TYPE_AUTO_CHANNEL to R.string.type_channel,
    CATEGORY_TYPE_AUTO_YEAR to R.string.type_year,
    CATEGORY_TYPE_AUTO_MONTH to R.string.type_month,
    CATEGORY_TYPE_AUTO_DURATION to R.string.type_duration,
    CATEGORY_TYPE_AUTO_YT_CATEGORY to R.string.dim_youtube_categories,
)

/**
 * Where this video appears in the Categories tab: one wrapping flow of chips across the whole
 * width, each opening that category's video list — "more from this channel / month / …" without a
 * detour through the tab. Emits nothing at all when the video has no auto categories (an unmatched
 * Jellyfin-only row), rather than a header over an empty flow.
 *
 * Each dimension's label sits inline ahead of its chips, and **the label travels with its first
 * chip**: the two are one item of the flow, so a break can never leave "Duration" hanging at the
 * end of a line with "60+ min" starting the next, which reads as two unrelated things. Every chip
 * after the first breaks freely, so a dimension with several values still fills the line it is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearsInSection(categories: List<Category>, onOpenCategory: (Category) -> Unit) {
    val byType = categories.groupBy { it.type }
    val groups = APPEARS_IN_DIMENSIONS.mapNotNull { (type, labelRes) ->
        byType[type]?.let { labelRes to it }
    }
    if (groups.isEmpty()) return

    Text(stringResource(R.string.appears_in), style = MaterialTheme.typography.titleMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // The chips are taller than the labels between them; tight rows would read as a table
        // that has lost its grid.
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEachIndexed { index, (labelRes, items) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                // A little more room ahead of a group than between chips, so each group reads as
                // one even when the flow puts two of them on one line. Not before the first,
                // which would indent the whole section away from everything above it.
                modifier = Modifier.padding(start = if (index == 0) 0.dp else 8.dp),
            ) {
                DimensionLabel(labelRes)
                CategoryChip(items.first(), onOpenCategory)
            }
            items.drop(1).forEach { CategoryChip(it, onOpenCategory) }
        }
    }
}

@Composable
private fun DimensionLabel(@StringRes labelRes: Int) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryChip(category: Category, onOpenCategory: (Category) -> Unit) {
    AssistChip(onClick = { onOpenCategory(category) }, label = { Text(category.name) })
}

/**
 * The weight of this screen's controls, inverted against Material's defaults on purpose: Play is
 * the one thing the screen exists for, so it is the tallest control on it, and the three things
 * done *about* a video sit below Material's 40 dp default rather than at it.
 *
 * Only the boxes change. Colour, label and order are what they were, and the 48 dp minimum touch
 * target is untouched either way — Material expands a short button's tap area beyond its paint
 * without making it look bigger, which is what keeps the shorter row from being harder to hit.
 */
private val PLAY_HEIGHT = 56.dp
private val PLAY_CONTENT_PADDING = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
private val PLAY_MENU_CONTENT_PADDING = PaddingValues(horizontal = 12.dp)
private val ACTION_MIN_HEIGHT = 36.dp

/** Wide enough for the longest of the three labels on one line, plus [ACTION_CONTENT_PADDING]. */
private val ACTION_MIN_WIDTH = 160.dp
private val ACTION_CONTENT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

// Hand-composed split-button halves: material3 1.4.0 (BOM 2026.06.01) ships only the SplitButton
// design *tokens*, not the SplitButtonLayout composable. Pill outer corners, small inner corners
// and a 2 dp gap are what read as "one button in two halves". The outer radius is half the
// button's height rather than a number of its own, so the pill stays a pill at [PLAY_HEIGHT].
private val SplitButtonStartShape = RoundedCornerShape(
    topStart = PLAY_HEIGHT / 2,
    bottomStart = PLAY_HEIGHT / 2,
    topEnd = 4.dp,
    bottomEnd = 4.dp,
)
private val SplitButtonEndShape = RoundedCornerShape(
    topStart = 4.dp,
    bottomStart = 4.dp,
    topEnd = PLAY_HEIGHT / 2,
    bottomEnd = PLAY_HEIGHT / 2,
)

/**
 * The split button's other half: the chevron and the mode menu behind it. Picking an item saves
 * the mode app-wide *and* acts with it immediately (split-button convention, like IDE run
 * buttons), acting with the tapped mode rather than the persisted flow value, which has not caught
 * up yet.
 *
 * Its own composable so demo mode can simply not render it — see [DetailContent], where the modes
 * it offers are both unreachable without a server.
 */
@Composable
@Suppress("LongParameterList") // the split button's other half; every parameter is its state
private fun PlaybackModeMenu(
    current: Video,
    settings: Settings?,
    itemId: String?,
    enabled: Boolean,
    launchThrottle: ClickThrottle,
    onPlay: (Video, Settings, PlaybackMode) -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
) {
    Box {
        var modeMenuOpen by remember { mutableStateOf(false) }
        Button(
            onClick = { modeMenuOpen = true },
            enabled = enabled,
            shape = SplitButtonEndShape,
            modifier = Modifier.heightIn(min = PLAY_HEIGHT),
            contentPadding = PLAY_MENU_CONTENT_PADDING,
        ) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.playback_options),
            )
        }
        DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
            PlaybackMode.entries.forEach { mode ->
                PlaybackModeMenuItem(
                    mode = mode,
                    selected = mode == settings?.playbackMode,
                    onClick = {
                        modeMenuOpen = false
                        if (settings != null && itemId != null) {
                            onSelectMode(mode)
                            launchThrottle { onPlay(current, settings, mode) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaybackModeMenuItem(mode: PlaybackMode, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(mode.labelRes)) },
        onClick = onClick,
        // Every item carries the slot so the labels align; only the saved mode shows the check.
        leadingIcon = {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
    )
}

private val PlaybackMode.labelRes: Int
    get() = when (this) {
        PlaybackMode.PLAY -> R.string.playback_mode_play
        PlaybackMode.EXTERNAL -> R.string.playback_mode_external
        PlaybackMode.WEB -> R.string.playback_mode_web
    }

/**
 * The same three modes named for the line under Play, where the word "Play" is already overhead:
 * one word each — "Internal" rather than the menu's "Play in app". Distinct strings rather than the
 * menu's, so the button never repeats a word from the line above it, and so neither string is a
 * duplicate of the other, which is what a screen reader and a test both have to tell apart.
 */
private val PlaybackMode.sourceLabelRes: Int
    get() = when (this) {
        PlaybackMode.PLAY -> R.string.playback_source_play
        PlaybackMode.EXTERNAL -> R.string.playback_source_external
        PlaybackMode.WEB -> R.string.playback_source_web
    }

/**
 * Shown when the server's listing has stopped including this video. Deliberately worded as
 * "missing", not "deleted": a Jellyfin rescan can drop a video from one listing and return it in
 * the next, which is exactly why sync waits several syncs before removing it locally.
 */
@Composable
private fun MissingFromServerNotice() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null)
            Text(
                stringResource(R.string.missing_from_server_explained),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A small colour-coded chip showing where this video's metadata came from. */
@Composable
private fun MetadataSourceBadge(source: String) {
    val (labelRes, container, content) = when (source) {
        METADATA_SOURCE_YTDLP -> Triple(
            R.string.source_ytdlp,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        METADATA_SOURCE_INDEX -> Triple(
            R.string.source_index,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        METADATA_SOURCE_API -> Triple(
            R.string.source_api,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        METADATA_SOURCE_API_INDEX -> Triple(
            R.string.source_api_index,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Triple(
            R.string.source_jellyfin,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
