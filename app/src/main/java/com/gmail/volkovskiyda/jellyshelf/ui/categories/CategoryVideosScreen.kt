package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.repository.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.VideoRow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberAnchoredLazyListState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryVideosScreen(
    categoryId: String,
    title: String,
    onVideoClick: (VideoEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = rememberContainer()
    val videos by remember(categoryId) {
        container.libraryRepository.observeVideosByCategory(categoryId)
    }.collectAsStateWithLifecycle(emptyList())

    var showDialog by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showDialog = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Create playlist",
                        )
                    }
                }
            },
        )
        if (videos.isEmpty()) {
            EmptyState("No videos in this category.")
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
            onDismiss = { showDialog = false },
            onCreate = { name -> container.libraryRepository.createPlaylistFromCategory(categoryId, name) },
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    defaultName: String,
    videoCount: Int,
    onDismiss: () -> Unit,
    onCreate: suspend (name: String) -> PlaylistResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(defaultName) }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
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
                onClick = {
                    creating = true
                    scope.launch {
                        val result = onCreate(name.trim())
                        creating = false
                        onDismiss()
                        val message = when (result) {
                            is PlaylistResult.Success ->
                                "Created \"${result.name}\" with ${result.count} video${if (result.count == 1) "" else "s"}"
                            is PlaylistResult.Error -> result.message
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
            ) { Text(if (creating) "Creating…" else "Create") }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
