package com.gmail.volkovskiyda.jellyshelf.ui.detail

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberClickThrottle
import com.gmail.volkovskiyda.jellyshelf.ui.rememberVideoThumbnailResolver
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import com.gmail.volkovskiyda.jellyshelf.util.formatTimestamp
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: DetailViewModel = koinViewModel { parametersOf(youtubeId) }

    // Launch the external player for a result; MX Player / VLC hand back the final position,
    // which we persist locally and report to Jellyfin as PlaybackStopped.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.tag(Playback.TAG).d("player returned: resultCode=${result.resultCode} hasData=${result.data != null}")
        val playback = Playback.parseResult(result.data)
            ?: return@rememberLauncherForActivityResult
        Timber.tag(Playback.TAG).d("reporting playback stopped: positionMs=${playback.positionMs} completed=${playback.completed}")
        viewModel.reportPlaybackStopped(playback.positionMs, playback.completed)
    }

    val videoState by viewModel.video.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val fetching by viewModel.fetching.collectAsStateWithLifecycle()

    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

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

    DetailContent(
        videoState = videoState,
        settings = settings,
        fetching = fetching,
        thumbnailModel = thumbnailModel,
        onBack = onBack,
        onPlay = { video, s, mode ->
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
internal fun DetailContent(
    videoState: VideoDetailState,
    settings: Settings?,
    fetching: Boolean,
    thumbnailModel: String?,
    onBack: () -> Unit,
    onPlay: (Video, Settings, PlaybackMode) -> Unit,
    onSelectMode: (PlaybackMode) -> Unit,
    onToggleWatched: () -> Unit,
    onFetchMetadata: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = (videoState as? VideoDetailState.Loaded)?.video

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(current?.channel ?: stringResource(R.string.video_fallback_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = thumbnailModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            Text(current.title, style = MaterialTheme.typography.titleLarge)

            val meta = buildList {
                current.channel?.let { add(it) }
                if (current.durationSeconds > 0) add(formatDuration(current.durationSeconds))
                formatUploadDate(current.uploadDate)?.let { add(it) }
            }.joinToString("  •  ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Provenance on one line: where the metadata came from, and when this row last saw a
            // sync. Omitted entirely for a never-synced video rather than printing an epoch date.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetadataSourceBadge(current.metadataSource)
                formatTimestamp(current.lastSyncedAt)?.let { syncedAt ->
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
                        formatTimestamp(current.lastFetchErrorAt).orEmpty(),
                        fetchError,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (current.missingFromServer) MissingFromServerNotice()

            // One playback control: a split button. The main half acts with the saved mode; the
            // chevron opens the mode menu, and picking an item saves the mode app-wide AND acts
            // with it immediately (split-button convention, like IDE run buttons). Every mode
            // leaves this screen one way or another, and the second tap of a double-tap would
            // land before whatever launched is on top — one shared throttle for the whole row.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val itemId = current.jellyfinItemId
                val s = settings
                val enabled = s != null && itemId != null
                val launchThrottle = rememberClickThrottle()
                Button(
                    onClick = {
                        if (s != null && itemId != null) {
                            launchThrottle { onPlay(current, s, s.playbackMode) }
                        }
                    },
                    enabled = enabled,
                    shape = SplitButtonStartShape,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.play))
                }
                Box {
                    var modeMenuOpen by remember { mutableStateOf(false) }
                    Button(
                        onClick = { modeMenuOpen = true },
                        enabled = enabled,
                        shape = SplitButtonEndShape,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(R.string.playback_options),
                        )
                    }
                    DropdownMenu(
                        expanded = modeMenuOpen,
                        onDismissRequest = { modeMenuOpen = false },
                    ) {
                        PlaybackMode.entries.forEach { mode ->
                            PlaybackModeMenuItem(
                                mode = mode,
                                selected = mode == s?.playbackMode,
                                onClick = {
                                    modeMenuOpen = false
                                    if (s != null && itemId != null) {
                                        // Save, then act with the *tapped* mode — the persisted
                                        // flow value hasn't caught up yet.
                                        onSelectMode(mode)
                                        launchThrottle { onPlay(current, s, mode) }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Watched toggle
            OutlinedButton(
                onClick = onToggleWatched,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (current.played) R.string.mark_unwatched else R.string.mark_watched
                    )
                )
            }

            // Fetch / refresh YouTube metadata in-app with the bundled yt-dlp. "Get" when the video
            // only has Jellyfin data, "Update" once it has index or yt-dlp metadata.
            val hasMetadata = current.metadataSource == METADATA_SOURCE_INDEX ||
                current.metadataSource == METADATA_SOURCE_YTDLP
            OutlinedButton(
                onClick = onFetchMetadata,
                enabled = !fetching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when {
                            fetching -> R.string.fetching_metadata
                            hasMetadata -> R.string.update_metadata
                            else -> R.string.get_metadata
                        }
                    )
                )
            }

            // Only offered once the server has stopped listing the video: for anything still in
            // the library, sync owns the local rows and a manual delete would just be undone.
            if (current.missingFromServer) {
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.remove_from_library)) }
            }

            current.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(stringResource(R.string.description), style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Hand-composed split-button halves: material3 1.4.0 (BOM 2026.06.01) ships only the SplitButton
// design *tokens*, not the SplitButtonLayout composable. Pill outer corners, small inner corners
// and a 2 dp gap are what read as "one button in two halves".
private val SplitButtonStartShape = RoundedCornerShape(
    topStart = 20.dp,
    bottomStart = 20.dp,
    topEnd = 4.dp,
    bottomEnd = 4.dp,
)
private val SplitButtonEndShape = RoundedCornerShape(
    topStart = 4.dp,
    bottomStart = 4.dp,
    topEnd = 20.dp,
    bottomEnd = 20.dp,
)

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
