package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Checklist
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_UNCATEGORIZED
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.BackButton
import com.gmail.volkovskiyda.jellyshelf.ui.BulkActionHeader
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.ToastOnMessage
import com.gmail.volkovskiyda.jellyshelf.ui.VideoRow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberAnchoredLazyListState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberVideoThumbnailResolver
import com.gmail.volkovskiyda.jellyshelf.ui.selection.CreatePlaylistDialog
import com.gmail.volkovskiyda.jellyshelf.ui.selection.SelectionActionDialog
import com.gmail.volkovskiyda.jellyshelf.ui.selection.SelectionRunHeader
import com.gmail.volkovskiyda.jellyshelf.ui.selection.SelectionTopBar
import com.gmail.volkovskiyda.jellyshelf.ui.selection.SelectionUndoSnackbar
import com.gmail.volkovskiyda.jellyshelf.ui.selection.playlistMessage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * Handle for `JourneyBenchmark`, published as a resource id by MainActivity's `testTagsAsResourceId`
 * — see the same constants in `LibraryScreen`. A row rather than the list, because a list exists
 * before it has anything in it and what a journey waits for is content. Distinct from the library's
 * own row tag so a journey can tell which screen it is looking at.
 */
internal const val CATEGORY_VIDEO_ROW_TAG = "category_video_row"

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
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val selection = viewModel.selection
    val selectionActive by selection.active.collectAsStateWithLifecycle()
    val selectedIds by selection.selected.collectAsStateWithLifecycle()
    val selectionRun by selection.run.collectAsStateWithLifecycle()
    val selectionUndo by selection.undo.collectAsStateWithLifecycle()
    val playlistDialogOpen by selection.playlistDialogOpen.collectAsStateWithLifecycle()
    val creatingPlaylist by selection.creatingPlaylist.collectAsStateWithLifecycle()

    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    val playlistResult by selection.playlistResult.collectAsStateWithLifecycle()
    ToastOnMessage(playlistResult?.let { playlistMessage(it) }, selection::consumePlaylistResult)

    CategoryVideosContent(
        title = title,
        videosOrNull = videosOrNull,
        bulkFetch = bulkFetch,
        bulkRemove = bulkRemove,
        demoMode = demoMode,
        isUncategorized = categoryId == VIRTUAL_CATEGORY_UNCATEGORIZED,
        removeKind = viewModel.removeKind,
        scrollKey = "category.$categoryId",
        showRemoveDialog = showRemoveDialog,
        onShowRemoveDialog = { showRemoveDialog = true },
        onDismissRemoveDialog = { showRemoveDialog = false },
        onPlayVideo = onPlayVideo,
        onOpenDetails = onOpenDetails,
        onBack = onBack,
        onStartFetchMissing = viewModel::startFetchMissing,
        onCancelFetchMissing = viewModel::cancelFetchMissing,
        onAcknowledgeBulkFetch = viewModel::acknowledgeBulkFetch,
        onConfirmRemove = {
            showRemoveDialog = false
            viewModel.startRemove()
        },
        onCancelRemove = viewModel::cancelRemove,
        onAcknowledgeBulkRemove = viewModel::acknowledgeBulkRemove,
        selectionActive = selectionActive,
        selectedIds = selectedIds,
        selectionRun = selectionRun,
        onStartSelection = selection::start,
        onToggleSelection = selection::toggle,
        onSelectAll = selection::selectAll,
        onDeselectAll = selection::deselectAll,
        onExitSelection = selection::exit,
        onSelectionAction = selection::startAction,
        onCancelSelectionRun = selection::cancelRun,
        onAcknowledgeSelectionRun = selection::acknowledgeRun,
        onCreatePlaylist = selection::openPlaylistDialog,
        playlistDialogOpen = playlistDialogOpen,
        creatingPlaylist = creatingPlaylist,
        playlistDefaultName = title,
        onDismissPlaylistDialog = selection::dismissPlaylistDialog,
        onConfirmPlaylist = selection::createPlaylist,
        modifier = modifier,
    )

    // Out here rather than inside the content, which is rendered on its own by previews and by the
    // behavior tests: the snackbar needs the Scaffold's host, and only MainActivity has one.
    SelectionUndoSnackbar(
        undo = selectionUndo,
        onUndo = selection::undoBulkChange,
        onConsumed = selection::consumeUndo,
    )
}

/**
 * The bulk removal a category offers, if any — the "Others" tab has two filters that offer one,
 * and they mean opposite things. [WATCHED] deletes the media on the Jellyfin server; [MISSING]
 * only drops rows for videos the server has already stopped listing, and touches no server at all.
 * Every label rides on the enum so the two can never be confused at a call site.
 */
internal enum class RemoveKind(
    @param:PluralsRes val idleLabel: Int,
    @param:StringRes val dialogTitle: Int,
    @param:StringRes val dialogText: Int,
    @param:StringRes val emptyLabel: Int,
) {
    WATCHED(
        idleLabel = R.plurals.remove_watched,
        dialogTitle = R.string.remove_watched_dialog_title,
        dialogText = R.string.remove_watched_dialog_text,
        emptyLabel = R.string.empty_category,
    ),
    MISSING(
        idleLabel = R.plurals.remove_missing,
        dialogTitle = R.string.remove_missing_dialog_title,
        dialogText = R.string.remove_missing_dialog_text,
        emptyLabel = R.string.empty_missing,
    ),
}

@Composable
internal fun CategoryVideosContent(
    title: String,
    videosOrNull: List<Video>?,
    bulkFetch: BulkProgress,
    bulkRemove: BulkProgress,
    demoMode: Boolean,
    isUncategorized: Boolean,
    removeKind: RemoveKind?,
    scrollKey: String,
    showRemoveDialog: Boolean,
    onShowRemoveDialog: () -> Unit,
    onDismissRemoveDialog: () -> Unit,
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    onBack: () -> Unit,
    onStartFetchMissing: () -> Unit,
    onCancelFetchMissing: () -> Unit,
    onAcknowledgeBulkFetch: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onAcknowledgeBulkRemove: () -> Unit,
    modifier: Modifier = Modifier,
    selectionActive: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    selectionRun: SelectionRun? = null,
    onStartSelection: (String?) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: (List<String>) -> Unit = {},
    onDeselectAll: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    onSelectionAction: (SelectionAction) -> Unit = {},
    onCancelSelectionRun: () -> Unit = {},
    onAcknowledgeSelectionRun: () -> Unit = {},
    onCreatePlaylist: (() -> Unit)? = null,
    playlistDialogOpen: Boolean = false,
    creatingPlaylist: Boolean = false,
    playlistDefaultName: String = "",
    onDismissPlaylistDialog: () -> Unit = {},
    onConfirmPlaylist: (String) -> Unit = {},
    // Injected by default; host-side rendering passes an in-memory stand-in.
    scrollStore: ScrollPositionRepository = koinInject(),
    // Resolved once for the whole screen, not per row — see [rememberVideoThumbnailResolver].
    thumbnailModel: (Video) -> String? = rememberVideoThumbnailResolver(),
) {
    val videos = videosOrNull.orEmpty()
    // See [LibraryContent] for why this is local state and Back is handled here.
    var pendingAction by rememberSaveable { mutableStateOf<SelectionAction?>(null) }
    BackHandler(enabled = selectionActive) { onExitSelection() }

    /**
     * Whether to *offer* one of this category's own bulk runs — the ones that act on the whole
     * filter rather than on a selection.
     *
     * Withdrawn while selecting, because the two are one strip apart and read as one thing:
     * "Remove 12 watched videos" sitting under "12 selected" looks like the button that acts on
     * those 12, and on the Watched filter the counts even agree. One of them deletes media on the
     * server, so the resemblance is not one to leave standing.
     *
     * Only the offer. A run already under way keeps its header (the conditions above test the
     * progress separately), because that header is the only place to watch it or cancel it, and
     * entering selection mode must not strand a removal with no way to stop it.
     */
    val offerCategoryWideRun = videos.isNotEmpty() && !selectionActive

    Column(modifier = modifier.fillMaxSize()) {
        if (selectionActive) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                canSelectAll = videos.any { it.youtubeId !in selectedIds },
                // Closed while a run is going: the repository takes one at a time.
                canAct = selectedIds.isNotEmpty() && selectionRun?.progress !is BulkProgress.Running,
                onExit = onExitSelection,
                onSelectAll = { onSelectAll(videos.map { it.youtubeId }) },
                onDeselectAll = onDeselectAll,
                onAction = { pendingAction = it },
                // No playlist offer on Missing from server: a playlist is built from Jellyfin item
                // ids, and every video here is one the server has stopped listing — the call can
                // only be rejected. Offering an action that cannot succeed is worse than not
                // offering it. Decided here rather than by the caller so the rule sits with the
                // `removeKind` it reads, where the behaviour tests can reach it.
                onCreatePlaylist = onCreatePlaylist?.takeIf { removeKind != RemoveKind.MISSING },
            )
        } else {
            CategoryTopBar(
                title = title,
                videoCount = videos.size,
                onBack = onBack,
                onStartSelection = { onStartSelection(null) },
            )
        }

        SelectionRunHeader(
            run = selectionRun,
            onCancel = onCancelSelectionRun,
            onDismiss = onAcknowledgeSelectionRun,
            selecting = selectionActive,
        )

        // The in-app yt-dlp bulk fetch lives only on the Uncategorized filter.
        if (isUncategorized && (bulkFetch !is BulkProgress.Idle || offerCategoryWideRun)) {
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

        // Bulk removal lives only on the two filters that have one (see [RemoveKind]). Either way
        // it is irreversible, so the button opens a confirmation rather than starting the run.
        if (removeKind != null && (bulkRemove !is BulkProgress.Idle || offerCategoryWideRun)) {
            BulkActionHeader(
                state = bulkRemove,
                idleLabel = pluralStringResource(removeKind.idleLabel, videos.size, videos.size),
                runningLabel = R.string.removing_progress,
                doneLabel = R.string.removed_summary,
                enabled = videos.isNotEmpty(),
                onStart = onShowRemoveDialog,
                onCancel = onCancelRemove,
                onDismiss = onAcknowledgeBulkRemove,
                destructive = true,
            )
        }

        CategoryVideoList(
            videosOrNull = videosOrNull,
            isUncategorized = isUncategorized,
            removeKind = removeKind,
            scrollKey = scrollKey,
            onPlayVideo = onPlayVideo,
            onOpenDetails = onOpenDetails,
            selectionActive = selectionActive,
            selectedIds = selectedIds,
            onStartSelection = onStartSelection,
            onToggleSelection = onToggleSelection,
            scrollStore = scrollStore,
            thumbnailModel = thumbnailModel,
        )
    }

    pendingAction?.let { action ->
        SelectionActionDialog(
            action = action,
            videoCount = selectedIds.size,
            demoMode = demoMode,
            onDismiss = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                onSelectionAction(action)
            },
        )
    }

    if (playlistDialogOpen) {
        CreatePlaylistDialog(
            defaultName = playlistDefaultName,
            videoCount = selectedIds.size,
            creating = creatingPlaylist,
            onDismiss = onDismissPlaylistDialog,
            onCreate = onConfirmPlaylist,
        )
    }

    if (showRemoveDialog && removeKind != null) {
        RemoveVideosDialog(
            kind = removeKind,
            videoCount = videos.size,
            demoMode = demoMode,
            onDismiss = onDismissRemoveDialog,
            onConfirm = onConfirmRemove,
        )
    }
}

/**
 * The category's title bar: back, count, Select. Create playlist used to sit here too and now
 * lives in the selection menu — building one from four chosen videos is what people wanted from
 * it, and a whole category was only ever the coarsest version of that.
 *
 * The count needs no end inset of its own: Select is offered wherever the count is, so there is
 * always a button after it holding the count off the edge of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTopBar(
    title: String,
    videoCount: Int,
    onBack: () -> Unit,
    onStartSelection: () -> Unit,
) {
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
                // Long-pressing a row is the other way into selection mode; this is the discoverable
                // one, and it is offered wherever there is a video to select.
                IconButton(onClick = onStartSelection) {
                    Icon(
                        Icons.Filled.Checklist,
                        contentDescription = stringResource(R.string.select_videos),
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
    removeKind: RemoveKind?,
    scrollKey: String,
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    selectionActive: Boolean,
    selectedIds: Set<String>,
    onStartSelection: (String?) -> Unit,
    onToggleSelection: (String) -> Unit,
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
                when {
                    isUncategorized -> R.string.empty_uncategorized
                    // Reachable by emptying the filter from the screen itself: the Others tab
                    // hides a filter whose count is zero, so it is never entered empty.
                    else -> removeKind?.emptyLabel ?: R.string.empty_category
                },
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
                    modifier = Modifier.testTag(CATEGORY_VIDEO_ROW_TAG),
                    onPlay = { onPlayVideo(video) },
                    onOpenDetails = { onOpenDetails(video) },
                    thumbnailModel = thumbnailModel(video),
                    selected = if (selectionActive) video.youtubeId in selectedIds else null,
                    onToggleSelection = { onToggleSelection(video.youtubeId) },
                    onStartSelection = { onStartSelection(video.youtubeId) },
                )
            }
        }
    }
}

/**
 * Confirms the bulk removal. Spelled out rather than a plain "are you sure", and spelled out
 * differently per [kind]: [RemoveKind.WATCHED] deletes the media on the server, which no re-sync
 * can bring back, while [RemoveKind.MISSING] only drops local rows and a video the server turns
 * out to still have returns on the next sync. Getting those two the wrong way round is exactly
 * the mistake this dialog exists to prevent.
 *
 * A demo says what a demo can honestly say instead — of the watched removal only, which is the
 * one that claims to reach a server. The removal is just as real there — the rows go — but there
 * is no server and no media file, and re-entering the demo restores the dataset.
 */
@Composable
private fun RemoveVideosDialog(
    kind: RemoveKind,
    videoCount: Int,
    demoMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(kind.dialogTitle)) },
        text = {
            Text(
                stringResource(
                    if (demoMode && kind == RemoveKind.WATCHED) {
                        R.string.remove_watched_dialog_text_demo
                    } else {
                        kind.dialogText
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
