package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gmail.volkovskiyda.jellyshelf.data.local.VIRTUAL_CATEGORY_UNCATEGORIZED
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.repository.BulkFetch
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.VideoRow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberAnchoredLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryVideosScreen(
    categoryId: String,
    title: String,
    onVideoClick: (VideoEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: CategoryVideosViewModel = viewModel {
        CategoryVideosViewModel(checkNotNull(this[APPLICATION_KEY]), categoryId)
    }
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val bulkFetch by viewModel.bulkFetch.collectAsStateWithLifecycle()
    val creating by viewModel.creatingPlaylist.collectAsStateWithLifecycle()

    val isUncategorized = categoryId == VIRTUAL_CATEGORY_UNCATEGORIZED
    var showDialog by rememberSaveable { mutableStateOf(false) }

    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            showDialog = false
            viewModel.consumeMessage()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (videos.isNotEmpty()) {
                    Text(
                        "${videos.size} video${if (videos.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { showDialog = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Create playlist",
                        )
                    }
                }
            },
        )

        // The in-app yt-dlp bulk fetch lives only on the Uncategorized filter.
        if (isUncategorized && (bulkFetch !is BulkFetch.Idle || videos.isNotEmpty())) {
            FetchMissingHeader(
                state = bulkFetch,
                missingCount = videos.size,
                onStart = viewModel::startFetchMissing,
                onCancel = viewModel::cancelFetchMissing,
                onDismiss = viewModel::acknowledgeBulkFetch,
            )
        }

        if (videos.isEmpty()) {
            EmptyState(
                if (isUncategorized) "No uncategorized videos — everything has metadata."
                else "No videos in this category.",
            )
        } else {
            LazyColumn(
                state = rememberAnchoredLazyListState("category.$categoryId", videos) { it.fileName },
                modifier = Modifier.fillMaxSize(),
            ) {
                items(videos, key = { it.youtubeId }) { video ->
                    VideoRow(video = video, onClick = { onVideoClick(video) })
                }
            }
        }
    }

    if (showDialog) {
        CreatePlaylistDialog(
            defaultName = title,
            videoCount = videos.size,
            creating = creating,
            onDismiss = { if (!creating) showDialog = false },
            onCreate = viewModel::createPlaylist,
        )
    }
}

/**
 * Header for the Uncategorized filter: kicks off (and reports on) the in-app yt-dlp fetch of
 * metadata for every video that only has Jellyfin data. Progress survives leaving the screen.
 */
@Composable
private fun FetchMissingHeader(
    state: BulkFetch,
    missingCount: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is BulkFetch.Running -> {
                val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append("Fetching metadata… ${state.done}/${state.total}")
                            if (state.failed > 0) append(" • ${state.failed} failed")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            is BulkFetch.Done -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append("Fetched ${state.total - state.failed}/${state.total}")
                            if (state.failed > 0) append(" • ${state.failed} failed")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }

            BulkFetch.Idle -> {
                Button(
                    onClick = onStart,
                    enabled = missingCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Fetch metadata for $missingCount missing")
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun CreatePlaylistDialog(
    defaultName: String,
    videoCount: Int,
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            Column {
                Text(
                    "$videoCount video${if (videoCount == 1) "" else "s"}, ordered by file name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.padding(top = 12.dp),
                    singleLine = true,
                    label = { Text("Playlist name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
            ) { Text(if (creating) "Creating…" else "Create") }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
