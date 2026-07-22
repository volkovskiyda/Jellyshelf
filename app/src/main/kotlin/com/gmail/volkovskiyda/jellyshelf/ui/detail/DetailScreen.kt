package com.gmail.volkovskiyda.jellyshelf.ui.detail

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberThumbnailModel
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import com.gmail.volkovskiyda.jellyshelf.util.formatUploadDate
import com.gmail.volkovskiyda.jellyshelf.util.ticksToMillis
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    youtubeId: String,
    onBack: () -> Unit,
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
                model = rememberThumbnailModel(current.thumbnailUrl),
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

            MetadataSourceBadge(current.metadataSource)

            // Playback actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val itemId = current.jellyfinItemId
                val s = settings
                Button(
                    onClick = {
                        if (s != null && itemId != null) {
                            playerLauncher.launch(
                                Playback.externalPlayerIntent(
                                    context = context,
                                    serverUrl = s.serverUrl,
                                    itemId = itemId,
                                    apiKey = s.apiKey,
                                    title = current.title,
                                    resumeMs = ticksToMillis(current.playbackPositionTicks),
                                )
                            )
                        }
                    },
                    enabled = s != null && itemId != null,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.play))
                }
                OutlinedButton(
                    onClick = {
                        if (s != null && itemId != null) Playback.openInJellyfin(context, s.serverUrl, itemId)
                    },
                    enabled = s != null && itemId != null,
                ) {
                    Text(stringResource(R.string.open_in_jellyfin))
                }
            }

            // Watched toggle
            OutlinedButton(
                onClick = { viewModel.toggleWatched() },
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
                onClick = { viewModel.fetchMetadata() },
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

            current.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(stringResource(R.string.description), style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
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
