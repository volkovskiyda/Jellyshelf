package com.gmail.volkovskiyda.jellyshelf.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.ui.rememberContainer
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import com.gmail.volkovskiyda.jellyshelf.util.formatUploadDate
import com.gmail.volkovskiyda.jellyshelf.util.ticksToMillis
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    youtubeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = rememberContainer()
    val repo = container.libraryRepository
    val scope = rememberCoroutineScope()

    // Launch the external player for a result; MX Player / VLC hand back the final position,
    // which we persist locally and report to Jellyfin as PlaybackStopped.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val playback = Playback.parseResult(result.data)
            ?: return@rememberLauncherForActivityResult
        scope.launch { repo.onPlaybackStopped(youtubeId, playback.positionMs, playback.completed) }
    }

    val video by remember(youtubeId) { repo.observeVideo(youtubeId) }
        .collectAsStateWithLifecycle(null)
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(null)

    val current = video

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(current?.channel ?: "Video") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {}
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
                model = current.thumbnailUrl,
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

            // Playback actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val itemId = current.jellyfinItemId
                val s = settings
                Button(
                    onClick = {
                        if (s != null && itemId != null) {
                            playerLauncher.launch(
                                Playback.externalPlayerIntent(
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
                    Text("Play")
                }
                OutlinedButton(
                    onClick = {
                        if (s != null && itemId != null) Playback.openInJellyfin(context, s.serverUrl, itemId)
                    },
                    enabled = s != null && itemId != null,
                ) {
                    Text("Open in Jellyfin")
                }
            }

            // Watched toggle
            OutlinedButton(
                onClick = { scope.launch { repo.setPlayed(youtubeId, !current.played) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (current.played) "Mark as unwatched" else "Mark as watched")
            }

            current.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text("Description", style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
