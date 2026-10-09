package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

/**
 * Where a [VideoList] is scrolled to, and which layout it is in: one video per row, or two per
 * row on an [expanded window][isExpandedWindow].
 *
 * The layout is decided here, when the state is remembered, so the state and the list drawn with
 * it can never disagree — a grid handed a list's state, or the other way round, is not a value
 * this type can hold.
 */
@Stable
sealed interface VideoListState {
    /** Back to the first video, as a new set of search results wants. */
    suspend fun scrollToTop()
}

private class SingleColumn(val state: LazyListState) : VideoListState {
    override suspend fun scrollToTop() = state.scrollToItem(0)
}

private class TwoColumns(val state: LazyGridState) : VideoListState {
    override suspend fun scrollToTop() = state.scrollToItem(0)
}

/**
 * A [VideoListState] for [videos]. With a [persistKey] the position is saved under it and anchored
 * to a video's file name (see [rememberAnchoredLazyListState]), so [videos] must be in file-name
 * order; without one it lives only as long as the composition does — search results, which start
 * from the top anyway.
 *
 * Turning a tablet on its side switches the layout, and the persisted position follows the switch:
 * both layouts anchor under the same key. A position that is not persisted starts again from the
 * top.
 */
@Composable
fun rememberVideoListState(
    videos: List<Video>,
    persistKey: String?,
    store: ScrollPositionRepository,
): VideoListState = if (isExpandedWindow()) {
    val state = if (persistKey != null) {
        rememberAnchoredLazyGridState(persistKey, videos, store) { it.fileName }
    } else {
        rememberLazyGridState()
    }
    remember(state) { TwoColumns(state) }
} else {
    val state = if (persistKey != null) {
        rememberAnchoredLazyListState(persistKey, videos, store) { it.fileName }
    } else {
        rememberLazyListState()
    }
    remember(state) { SingleColumn(state) }
}

/**
 * [videos] as [row]s, one per line — or, on a landscape tablet, two side by side. One per line
 * there left each row's text column ending halfway across a 1,200 dp list, and the watched tick
 * stranded at the far edge; two columns put the width to use and show twice the videos per screen.
 *
 * Keyed by YouTube id in both layouts, so a row keeps its place (and its state) across a content
 * change rather than its index.
 */
@Composable
fun VideoList(
    state: VideoListState,
    videos: List<Video>,
    modifier: Modifier = Modifier,
    row: @Composable (Video) -> Unit,
) {
    when (state) {
        is SingleColumn -> LazyColumn(state = state.state, modifier = modifier.fillMaxSize()) {
            listItems(videos, key = { it.youtubeId }) { row(it) }
        }

        is TwoColumns -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state.state,
            modifier = modifier.fillMaxSize(),
        ) {
            gridItems(videos, key = { it.youtubeId }) { row(it) }
        }
    }
}
