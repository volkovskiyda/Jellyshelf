package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.VideoRow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberAnchoredLazyListState
import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onVideoClick: (VideoEntity) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val durationFilter by viewModel.durationFilter.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                if (totalCount > 0) {
                    // Bare total when unfiltered, filtered/all once a duration is picked. The
                    // shown list is the filtered set (duration is a hard filter; search only
                    // reorders), so its size is the numerator.
                    val label = if (durationFilter == null) "$totalCount" else "${videos.size}/$totalCount"
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DurationFilterAction(
                    selected = durationFilter,
                    onSelect = viewModel::onDurationFilterChange,
                )
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search title or channel") },
        )
        if (videos.isEmpty()) {
            val message = when {
                durationFilter != null -> "No videos with a ${durationFilter!!.label} duration."
                else -> "No videos yet. Connect to Jellyfin in Settings and run a sync."
            }
            EmptyState(message)
        } else {
            // Only the pristine list — no query, no filter — restores its scroll position. Search
            // and filter results are transient, reordered sets and start from the top.
            val listState = if (query.isBlank() && durationFilter == null) {
                rememberAnchoredLazyListState("library", videos) { it.fileName }
            } else {
                rememberLazyListState()
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(videos, key = { it.youtubeId }) { video ->
                    VideoRow(video = video, onClick = { onVideoClick(video) })
                }
            }
        }
    }
}

@Composable
private fun DurationFilterAction(
    selected: DurationBucket?,
    onSelect: (DurationBucket?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            Icons.Filled.FilterList,
            contentDescription = "Filter by duration",
            tint = if (selected != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DurationMenuItem("Any duration", selected == null) {
            onSelect(null)
            expanded = false
        }
        for (bucket in DurationBucket.entries) {
            DurationMenuItem(bucket.label, selected == bucket) {
                onSelect(bucket)
                expanded = false
            }
        }
    }
}

@Composable
private fun DurationMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { RadioButton(selected = checked, onClick = onClick) },
    )
}
