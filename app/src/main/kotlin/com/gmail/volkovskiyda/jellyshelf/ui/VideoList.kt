package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository

/**
 * Where a [VideoList] of [videos] is scrolled to. With a [persistKey] the position is saved under
 * it and anchored to a video's file name (see [rememberAnchoredLazyGridState]), so [videos] must
 * be in file-name order; without one it lives only as long as the composition does — search
 * results, which start from the top anyway.
 */
@Composable
fun rememberVideoListState(
    videos: List<Video>,
    persistKey: String?,
    store: ScrollPositionRepository,
): LazyGridState = if (persistKey != null) {
    rememberAnchoredLazyGridState(persistKey, videos, store) { it.fileName }
} else {
    rememberLazyGridState()
}

/**
 * [videos] as [row]s, in as many columns as the width holds rows of at least [videoRowMinWidth]:
 * one on a phone held upright, two on a phone on its side or a tablet either way up, three on a
 * desktop-sized window. Every column is the same width, sharing out whatever is left over.
 *
 * Decided by the width the grid actually gets rather than by the window class, so the rail, a
 * split screen or a resized desktop window all land on the column count that fits. A single
 * column is the grid's own case of that, so there is one layout, one scroll state and one anchor
 * however the window changes: turning a tablet keeps the same video at the top.
 *
 * Keyed by YouTube id, so a row keeps its place (and its state) across a content change rather
 * than its index.
 */
@Composable
fun VideoList(
    state: LazyGridState,
    videos: List<Video>,
    modifier: Modifier = Modifier,
    row: @Composable (Video) -> Unit,
) {
    val minWidth = videoRowMinWidth()
    LazyVerticalGrid(
        columns = remember(minWidth) { GridCells.Adaptive(minWidth) },
        state = state,
        modifier = modifier.fillMaxSize(),
    ) {
        items(videos, key = { it.youtubeId }) { row(it) }
    }
}
