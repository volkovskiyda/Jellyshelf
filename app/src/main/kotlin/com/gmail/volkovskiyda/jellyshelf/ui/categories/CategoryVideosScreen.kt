package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_UNCATEGORIZED
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_WATCHED
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.BackButton
import com.gmail.volkovskiyda.jellyshelf.ui.DestructiveButton
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.ToastOnMessage
import com.gmail.volkovskiyda.jellyshelf.ui.VideoRow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberAnchoredLazyListState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberVideoThumbnailResolver
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * One category's videos: binds [CategoryVideosViewModel] to the stateless
 * [CategoryVideosContent] below, which previews and tests can render on its own.
 */
@Composable
fun CategoryVideosScreen(
    categoryId: String,
    title: String,
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CategoryVideosViewModel = koinViewModel { parametersOf(categoryId) }
    val videosOrNull by viewModel.videos.collectAsStateWithLifecycle()
    val bulkFetch by viewModel.bulkFetch.collectAsStateWithLifecycle()
    val bulkRemove by viewModel.bulkRemove.collectAsStateWithLifecycle()
    val creating by viewModel.creatingPlaylist.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()

    // Dialog visibility lives here, not in the content: a finished playlist creation reports via
    // [message], and that same signal is what closes the dialog.
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    val message by viewModel.message.collectAsStateWithLifecycle()
    ToastOnMessage(message) {
        showDialog = false
        viewModel.consumeMessage()
    }

    CategoryVideosContent(
        title = title,
        videosOrNull = videosOrNull,
        bulkFetch = bulkFetch,
        bulkRemove = bulkRemove,
        creating = creating,
        demoMode = demoMode,
        isUncategorized = categoryId == VIRTUAL_CATEGORY_UNCATEGORIZED,
        isWatched = categoryId == VIRTUAL_CATEGORY_WATCHED,
        scrollKey = "category.$categoryId",
        showDialog = showDialog,
        onShowDialog = { showDialog = true },
        onDismissDialog = { if (!creating) showDialog = false },
        showRemoveDialog = showRemoveDialog,
        onShowRemoveDialog = { showRemoveDialog = true },
        onDismissRemoveDialog = { showRemoveDialog = false },
        onPlayVideo = onPlayVideo,
        onOpenDetails = onOpenDetails,
        onBack = onBack,
        onStartFetchMissing = viewModel::startFetchMissing,
        onCancelFetchMissing = viewModel::cancelFetchMissing,
        onAcknowledgeBulkFetch = viewModel::acknowledgeBulkFetch,
        onConfirmRemoveWatched = {
            showRemoveDialog = false
            viewModel.startRemoveWatched()
        },
        onCancelRemoveWatched = viewModel::cancelRemoveWatched,
        onAcknowledgeBulkRemove = viewModel::acknowledgeBulkRemove,
        onCreatePlaylist = viewModel::createPlaylist,
        modifier = modifier,
    )
}

@Composable
internal fun CategoryVideosContent(
    title: String,
    videosOrNull: List<Video>?,
    bulkFetch: BulkProgress,
    bulkRemove: BulkProgress,
    creating: Boolean,
    demoMode: Boolean,
    isUncategorized: Boolean,
    isWatched: Boolean,
    scrollKey: String,
    showDialog: Boolean,
    onShowDialog: () -> Unit,
    onDismissDialog: () -> Unit,
    showRemoveDialog: Boolean,
    onShowRemoveDialog: () -> Unit,
    onDismissRemoveDialog: () -> Unit,
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    onBack: () -> Unit,
    onStartFetchMissing: () -> Unit,
    onCancelFetchMissing: () -> Unit,
    onAcknowledgeBulkFetch: () -> Unit,
    onConfirmRemoveWatched: () -> Unit,
    onCancelRemoveWatched: () -> Unit,
    onAcknowledgeBulkRemove: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Injected by default; host-side rendering passes an in-memory stand-in.
    scrollStore: ScrollPositionRepository = koinInject(),
    // Resolved once for the whole screen, not per row — see [rememberVideoThumbnailResolver].
    thumbnailModel: (Video) -> String? = rememberVideoThumbnailResolver(),
) {
    val videos = videosOrNull.orEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        CategoryTopBar(title, videos.size, onBack, onShowDialog)

        // The in-app yt-dlp bulk fetch lives only on the Uncategorized filter.
        if (isUncategorized && (bulkFetch !is BulkProgress.Idle || videos.isNotEmpty())) {
            BulkActionHeader(
                state = bulkFetch,
                idleLabel = stringResource(R.string.fetch_missing, videos.size),
                runningLabel = R.string.fetching_progress,
                doneLabel = R.string.fetched_summary,
                enabled = videos.isNotEmpty(),
                onStart = onStartFetchMissing,
                onCancel = onCancelFetchMissing,
                onDismiss = onAcknowledgeBulkFetch,
            )
        }

        // Bulk removal lives only on the Watched filter. It deletes on the server, so the button
        // opens a confirmation rather than starting the run.
        if (isWatched && (bulkRemove !is BulkProgress.Idle || videos.isNotEmpty())) {
            BulkActionHeader(
                state = bulkRemove,
                idleLabel = pluralStringResource(R.plurals.remove_watched, videos.size, videos.size),
                runningLabel = R.string.removing_progress,
                doneLabel = R.string.removed_summary,
                enabled = videos.isNotEmpty(),
                onStart = onShowRemoveDialog,
                onCancel = onCancelRemoveWatched,
                onDismiss = onAcknowledgeBulkRemove,
                destructive = true,
            )
        }

        CategoryVideoList(
            videosOrNull = videosOrNull,
            isUncategorized = isUncategorized,
            scrollKey = scrollKey,
            onPlayVideo = onPlayVideo,
            onOpenDetails = onOpenDetails,
            scrollStore = scrollStore,
            thumbnailModel = thumbnailModel,
        )
    }

    if (showDialog) {
        CreatePlaylistDialog(
            defaultName = title,
            videoCount = videos.size,
            creating = creating,
            onDismiss = onDismissDialog,
            onCreate = onCreatePlaylist,
        )
    }

    if (showRemoveDialog) {
        RemoveWatchedDialog(
            videoCount = videos.size,
            demoMode = demoMode,
            onDismiss = onDismissRemoveDialog,
            onConfirm = onConfirmRemoveWatched,
        )
    }
}

/** The category's title bar: back, count, and — once there is anything to play — Create playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTopBar(title: String, videoCount: Int, onBack: () -> Unit, onShowDialog: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { BackButton(onClick = onBack) },
        actions = {
            if (videoCount > 0) {
                Text(
                    pluralStringResource(R.plurals.video_count, videoCount, videoCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onShowDialog) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = stringResource(R.string.create_playlist),
                    )
                }
            }
        },
    )
}

@Composable
@Suppress("LongParameterList") // the list and its two per-row targets; every parameter is state
private fun CategoryVideoList(
    videosOrNull: List<Video>?,
    isUncategorized: Boolean,
    scrollKey: String,
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    scrollStore: ScrollPositionRepository,
    thumbnailModel: (Video) -> String?,
) {
    val videos = videosOrNull.orEmpty()
    if (videosOrNull == null) {
        // First Room emission still pending — don't flash the empty-state guidance.
        LoadingState()
    } else if (videos.isEmpty()) {
        EmptyState(
            stringResource(
                if (isUncategorized) R.string.empty_uncategorized else R.string.empty_category,
            ),
        )
    } else {
        LazyColumn(
            state = rememberAnchoredLazyListState(scrollKey, videos, scrollStore) { it.fileName },
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.youtubeId }) { video ->
                VideoRow(
                    video = video,
                    onPlay = { onPlayVideo(video) },
                    onOpenDetails = { onOpenDetails(video) },
                    thumbnailModel = thumbnailModel(video),
                )
            }
        }
    }
}

/**
 * Header for a bulk run over the current filter: a button that starts it, live progress with a
 * Cancel while it runs, and a summary with a Dismiss once it finishes. The state comes from the
 * repository, so progress survives leaving the screen and coming back.
 *
 * [runningLabel] and [doneLabel] each take done and total, in that order; [idleLabel] is resolved
 * by the caller because its count is pluralized differently per action. [destructive] renders the
 * start button in the error colour — the caller is expected to confirm before acting on it.
 */
@Composable
private fun BulkActionHeader(
    state: BulkProgress,
    idleLabel: String,
    @StringRes runningLabel: Int,
    @StringRes doneLabel: Int,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is BulkProgress.Running -> {
                val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                BulkStatusRow(
                    text = bulkLabel(runningLabel, state.done, state.total, state.failed),
                    actionLabel = stringResource(R.string.cancel),
                    onAction = onCancel,
                )
            }

            is BulkProgress.Done -> BulkStatusRow(
                text = bulkLabel(doneLabel, state.total - state.failed, state.total, state.failed),
                actionLabel = stringResource(R.string.dismiss),
                onAction = onDismiss,
            )

            BulkProgress.Idle -> BulkStartButton(idleLabel, enabled, destructive, onStart)
        }
    }
    HorizontalDivider()
}

/** "N of M" progress or summary text, with the failure count appended once there is one. */
@Composable
private fun bulkLabel(@StringRes label: Int, done: Int, total: Int, failed: Int): String = buildString {
    append(stringResource(label, done, total))
    if (failed > 0) {
        append(" • ")
        append(stringResource(R.string.failed_count, failed))
    }
}

@Composable
private fun BulkStatusRow(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun BulkStartButton(label: String, enabled: Boolean, destructive: Boolean, onStart: () -> Unit) {
    if (destructive) {
        DestructiveButton(label = label, onClick = onStart, enabled = enabled)
    } else {
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label) }
    }
}

/**
 * Confirms the bulk removal. Spelled out rather than a plain "are you sure": this deletes the
 * media on the server, which no re-sync can bring back.
 *
 * A demo says what a demo can honestly say instead. The removal is just as real there — the rows
 * go — but there is no server and no media file, and re-entering the demo restores the dataset.
 */
@Composable
private fun RemoveWatchedDialog(
    videoCount: Int,
    demoMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_watched_dialog_title)) },
        text = {
            Text(
                stringResource(
                    if (demoMode) {
                        R.string.remove_watched_dialog_text_demo
                    } else {
                        R.string.remove_watched_dialog_text
                    },
                    pluralStringResource(R.plurals.video_count, videoCount, videoCount),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
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
        title = { Text(stringResource(R.string.create_playlist)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.playlist_dialog_summary,
                        pluralStringResource(R.plurals.video_count, videoCount, videoCount),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.padding(top = 12.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.playlist_name)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
            ) { Text(stringResource(if (creating) R.string.creating else R.string.create)) }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
