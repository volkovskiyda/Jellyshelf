package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.VideoRow
import com.gmail.volkovskiyda.jellyshelf.ui.rememberThumbnailModel
import com.gmail.volkovskiyda.jellyshelf.ui.rememberAnchoredLazyListState
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Library tab: binds [LibraryViewModel] to the stateless [LibraryContent] below. Everything this
 * layer does is collect state and forward callbacks, so [LibraryContent] can be rendered by
 * previews, screenshot tests and behavior tests without a ViewModel or a Koin container.
 */
@Composable
fun LibraryScreen(
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val videosOrNull by viewModel.videos.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val durationFilter by viewModel.durationFilter.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()

    LibraryContent(
        videosOrNull = videosOrNull,
        query = query,
        durationFilter = durationFilter,
        totalCount = totalCount,
        onQueryChange = viewModel::onQueryChange,
        onDurationFilterChange = viewModel::onDurationFilterChange,
        onVideoClick = onVideoClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryContent(
    videosOrNull: LibraryVideos?,
    query: String,
    durationFilter: DurationBucket?,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onDurationFilterChange: (DurationBucket?) -> Unit,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    // Injected by default; host-side rendering passes an in-memory stand-in.
    scrollStore: ScrollPositionRepository = koinInject(),
    // Per-row thumbnail resolution, which reads the api key out of Koin — see VideoRow.
    thumbnailModel: @Composable (Video) -> String? = { rememberThumbnailModel(it.thumbnailUrl) },
) {
    val videos = videosOrNull?.items.orEmpty()
    // Everything describing the shown list reads the terms tagged on the emission, not the live
    // [query]/[durationFilter]: the query blanks a frame before the unfiltered list re-emits (and
    // leads it by a debounce while typing), so trusting the live ones here would restore scroll
    // against stale results and label the list by narrowing it hasn't had applied yet.
    val pristine = videosOrNull?.pristine == true
    val shownQuery = videosOrNull?.query.orEmpty()
    val shownDurationFilter = videosOrNull?.durationFilter

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                if (totalCount > 0) {
                    // Bare total only when nothing narrows the list, shown/all otherwise. Both the
                    // duration filter and the search are hard filters (SearchRanking drops
                    // non-matches as well as reordering it), so the shown list is the narrowed set
                    // and its size is the numerator — matching the contentDescription below.
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
                DurationFilterAction(
                    selected = durationFilter,
                    onSelect = onDurationFilterChange,
                )
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_search))
                    }
                }
            },
            placeholder = { Text(stringResource(R.string.search)) },
        )
        if (videosOrNull == null) {
            // First Room emission still pending — don't flash the empty-state guidance.
            LoadingState()
        } else if (videos.isEmpty()) {
            // Empty because the search or the filter matched nothing, not because the library is —
            // a synced user typing a miss must not be told to go and run a sync.
            val message = when {
                shownQuery.isNotBlank() && shownDurationFilter != null -> stringResource(
                    R.string.no_videos_match_duration,
                    shownDurationFilter.label,
                    shownQuery,
                )
                shownQuery.isNotBlank() -> stringResource(R.string.no_videos_match, shownQuery)
                shownDurationFilter != null ->
                    stringResource(R.string.empty_duration_filter, shownDurationFilter.label)
                else -> stringResource(R.string.empty_library)
            }
            EmptyState(message)
        } else {
            // Only the pristine list — no query, no filter — restores its scroll position: leaving
            // it saves the position, returning to it (query cleared, filter removed) restores it.
            // Gating on the emission's own pristine flag (not the query) means the anchored state
            // mounts only once the unfiltered list is actually back, so it restores against the
            // right contents. Search/filter results are transient, reordered sets that start from
            // the top and jump back on every keystroke or filter change to surface the best matches.
            val listState = if (pristine) {
                rememberAnchoredLazyListState("library", videos, scrollStore) { it.fileName }
            } else {
                rememberLazyListState()
            }
            if (!pristine) {
                // Jump to the top when the search terms change (a new keystroke or duration
                // filter) so the best matches lead — but not when the screen merely re-enters
                // composition after visiting a video and pressing back. Fire on the new list, not
                // on the query: the list is keyed by youtubeId, so LazyColumn keeps the anchored
                // item in view across a content change. Scrolling on the query (a frame before the
                // list updates) resets the old list, then key preservation drags us to wherever
                // that item ranks in the new one — visible when removing a character broadens the
                // results. Scrolling once the new list is applied overrides that. The saveable
                // guard still lets a return-from-navigation (same signature) skip the reset.
                val searchKey = "$query ${durationFilter?.name}"
                var lastSearchKey by rememberSaveable { mutableStateOf(searchKey) }
                LaunchedEffect(videos) {
                    if (searchKey != lastSearchKey) {
                        lastSearchKey = searchKey
                        listState.scrollToItem(0)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(videos, key = { it.youtubeId }) { video ->
                    VideoRow(
                        video = video,
                        onClick = { onVideoClick(video) },
                        thumbnailModel = thumbnailModel(video),
                    )
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
            contentDescription = stringResource(R.string.filter_by_duration),
            tint = if (selected != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DurationMenuItem(stringResource(R.string.any_duration), selected == null) {
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
        // Display-only radio (onClick = null): the menu item is the single accessible target,
        // instead of TalkBack seeing two nested clickables per row.
        leadingIcon = { RadioButton(selected = checked, onClick = null) },
    )
}
