package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.SearchField
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

/**
 * Handles for the baseline-profile generator (`:baselineprofile`), which drives this screen through
 * UiAutomator and so cannot match on Compose semantics the way the instrumented tests do. Named in
 * resource-id style because that is what they become: MainActivity's root Scaffold sets
 * `testTagsAsResourceId`, which republishes every tag below it as an Android resource id. They have
 * no other meaning — nothing in the app or the test suite reads them.
 */
internal const val LIBRARY_LIST_TAG = "library_list"
internal const val LIBRARY_ROW_TAG = "library_row"

/**
 * Library tab: binds [LibraryViewModel] to the stateless [LibraryContent] below. Everything this
 * layer does is collect state and forward callbacks, so [LibraryContent] can be rendered by
 * previews, screenshot tests and behavior tests without a ViewModel or a Koin container.
 */
@Composable
fun LibraryScreen(
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val videosOrNull by viewModel.videos.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val selection = viewModel.selection
    val selectionActive by selection.active.collectAsStateWithLifecycle()
    val selectedIds by selection.selected.collectAsStateWithLifecycle()
    val selectionRun by selection.run.collectAsStateWithLifecycle()
    val selectionUndo by selection.undo.collectAsStateWithLifecycle()
    val playlistDialogOpen by selection.playlistDialogOpen.collectAsStateWithLifecycle()
    val creatingPlaylist by selection.creatingPlaylist.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val playlistResult by selection.playlistResult.collectAsStateWithLifecycle()
    ToastOnMessage(playlistResult?.let { playlistMessage(it) }, selection::consumePlaylistResult)

    LibraryContent(
        videosOrNull = videosOrNull,
        query = query,
        totalCount = totalCount,
        onQueryChange = viewModel::onQueryChange,
        onPlayVideo = onPlayVideo,
        onOpenDetails = onOpenDetails,
        selectionActive = selectionActive,
        selectedIds = selectedIds,
        selectionRun = selectionRun,
        demoMode = demoMode,
        onStartSelection = selection::start,
        onToggleSelection = selection::toggle,
        onSelectAll = selection::selectAll,
        onDeselectAll = selection::deselectAll,
        onExitSelection = selection::exit,
        onSelectionAction = selection::startAction,
        onCancelSelectionRun = selection::cancelRun,
        onAcknowledgeSelectionRun = selection::acknowledgeRun,
        // Always offered here. Every list this screen shows is one a playlist can be built from —
        // the one filter that cannot (Missing from server) lives on the Categories tab.
        onCreatePlaylist = selection::openPlaylistDialog,
        playlistDialogOpen = playlistDialogOpen,
        creatingPlaylist = creatingPlaylist,
        onDismissPlaylistDialog = selection::dismissPlaylistDialog,
        onConfirmPlaylist = selection::createPlaylist,
        modifier = modifier,
    )

    // Both hosted out here rather than inside [LibraryContent], so the content stays renderable on
    // its own: the prompt keeps the screenshot goldens showing a screen with nothing on top of it,
    // and the snackbar needs the Scaffold's host, which only exists under MainActivity.
    //
    // The prompt is gated on the total, not on the rendered list: a search that matches nothing is
    // still a library with videos in it.
    NotificationPermissionPrompt(hasVideos = totalCount > 0)
    SelectionUndoSnackbar(
        undo = selectionUndo,
        onUndo = selection::undoBulkChange,
        onConsumed = selection::consumeUndo,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryContent(
    videosOrNull: LibraryVideos?,
    query: String,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onPlayVideo: (Video) -> Unit,
    onOpenDetails: (Video) -> Unit,
    selectionActive: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    selectionRun: SelectionRun? = null,
    demoMode: Boolean = false,
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
    onDismissPlaylistDialog: () -> Unit = {},
    onConfirmPlaylist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    // Injected by default; host-side rendering passes an in-memory stand-in.
    scrollStore: ScrollPositionRepository = koinInject(),
    // Resolved once for the whole screen, not per row — see [rememberVideoThumbnailResolver].
    thumbnailModel: (Video) -> String? = rememberVideoThumbnailResolver(),
) {
    val videos = videosOrNull?.items.orEmpty()
    // Everything describing the shown list reads the query tagged on the emission, not the live
    // [query]: it blanks a frame before the unfiltered list re-emits (and leads it by a debounce
    // while typing), so trusting the live one here would restore scroll against stale results and
    // label the list by a search it hasn't had applied yet.
    val pristine = videosOrNull?.pristine == true
    val shownQuery = videosOrNull?.query.orEmpty()

    // The action a confirmation dialog is currently standing in front of. Local rather than
    // hoisted: nothing outside this screen opens or closes it — confirming does, and so does
    // dismissing — unlike the playlist dialog on the category screen, which a finished creation
    // has to be able to close from the ViewModel.
    var pendingAction by rememberSaveable { mutableStateOf<SelectionAction?>(null) }

    // Back leaves selection mode instead of the screen. Registered below NavDisplay's own handler,
    // so it wins while it is enabled and gets out of the way the moment the mode ends.
    BackHandler(enabled = selectionActive) { onExitSelection() }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectionActive) {
            SelectionTopBar(
                selectedCount = selectedIds.size,
                // Only the videos on screen — under a search, that is not the whole library, and
                // what the user is looking at is the only thing they can judge an action against.
                canSelectAll = videos.any { it.youtubeId !in selectedIds },
                // Closed while a run is going: the repository takes one at a time.
                canAct = selectedIds.isNotEmpty() && selectionRun?.progress !is BulkProgress.Running,
                onExit = onExitSelection,
                onSelectAll = { onSelectAll(videos.map { it.youtubeId }) },
                onDeselectAll = onDeselectAll,
                onAction = { pendingAction = it },
                onCreatePlaylist = onCreatePlaylist,
            )
        } else {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (totalCount > 0) {
                        // Bare total only when nothing narrows the list, shown/all otherwise. The
                        // search is a hard filter (SearchRanking drops non-matches as well as
                        // reordering), so the shown list is the narrowed set and its size is the
                        // numerator — matching the contentDescription below.
                        val label = if (pristine) "$totalCount" else "${videos.size}/$totalCount"
                        val shownDescription =
                            pluralStringResource(R.plurals.shown_of_total, totalCount, videos.size, totalCount)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { contentDescription = shownDescription },
                        )
                    }
                    // Offered only once there is something to select. Long-pressing a row is the
                    // other way in, and the one most users will find first.
                    if (videos.isNotEmpty()) {
                        IconButton(onClick = { onStartSelection(null) }) {
                            Icon(
                                Icons.Filled.Checklist,
                                contentDescription = stringResource(R.string.select_videos),
                            )
                        }
                    }
                },
            )
        }
        SelectionRunHeader(
            run = selectionRun,
            onCancel = onCancelSelectionRun,
            onDismiss = onAcknowledgeSelectionRun,
            selecting = selectionActive,
        )
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.search),
            // Selection mode tints the bar above and every selected row below, which left the
            // strip the search sits in as a band of plain surface between the two — reading as a
            // gap in the mode rather than a part of it. Only the surface changes: the modifier
            // sits ahead of SearchField's own padding, so the tint fills the strip and the field
            // drawn inside it is untouched. Search stays usable while selecting, which is how a
            // selection gets made across a narrowed list in the first place.
            modifier = if (selectionActive) {
                Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
            } else {
                Modifier
            },
        )
        if (videosOrNull == null) {
            // First Room emission still pending — don't flash the empty-state guidance.
            LoadingState()
        } else if (videos.isEmpty()) {
            // Empty because the search matched nothing, not because the library is — a synced
            // user typing a miss must not be told to go and run a sync.
            val message = if (shownQuery.isNotBlank()) {
                stringResource(R.string.no_videos_match, shownQuery)
            } else {
                stringResource(R.string.empty_library)
            }
            EmptyState(message)
        } else {
            // Only the pristine list — no query — restores its scroll position: leaving it saves
            // the position, returning to it with the query cleared restores it. Gating on the
            // emission's own pristine flag (not the live query) means the anchored state mounts
            // only once the unfiltered list is actually back, so it restores against the right
            // contents. Search results are transient, reordered sets that start from the top and
            // jump back on every keystroke to surface the best matches.
            val listState = if (pristine) {
                rememberAnchoredLazyListState("library", videos, scrollStore) { it.fileName }
            } else {
                rememberLazyListState()
            }
            if (!pristine) {
                // Jump to the top when the query changes so the best matches lead — but not when
                // the screen merely re-enters composition after visiting a video and pressing
                // back. Fire on the new list, not
                // on the query: the list is keyed by youtubeId, so LazyColumn keeps the anchored
                // item in view across a content change. Scrolling on the query (a frame before the
                // list updates) resets the old list, then key preservation drags us to wherever
                // that item ranks in the new one — visible when removing a character broadens the
                // results. Scrolling once the new list is applied overrides that. The saveable
                // guard still lets a return-from-navigation (same signature) skip the reset.
                var lastQuery by rememberSaveable { mutableStateOf(query) }
                LaunchedEffect(videos) {
                    if (query != lastQuery) {
                        lastQuery = query
                        listState.scrollToItem(0)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(LIBRARY_LIST_TAG),
            ) {
                items(videos, key = { it.youtubeId }) { video ->
                    VideoRow(
                        video = video,
                        onPlay = { onPlayVideo(video) },
                        onOpenDetails = { onOpenDetails(video) },
                        thumbnailModel = thumbnailModel(video),
                        modifier = Modifier.testTag(LIBRARY_ROW_TAG),
                        selected = if (selectionActive) video.youtubeId in selectedIds else null,
                        onToggleSelection = { onToggleSelection(video.youtubeId) },
                        onStartSelection = { onStartSelection(video.youtubeId) },
                    )
                }
            }
        }
    }

    if (playlistDialogOpen) {
        CreatePlaylistDialog(
            // No default: a selection out of the whole library is not named by anything on screen,
            // unlike a category, and a wrong suggestion is worse than an empty field.
            defaultName = "",
            videoCount = selectedIds.size,
            creating = creatingPlaylist,
            onDismiss = onDismissPlaylistDialog,
            onCreate = onConfirmPlaylist,
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
}
