package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import kotlin.math.ceil

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
 * [videos] as [row]s in [videoGridColumns] equal columns — one on a phone held upright, two on a
 * phone on its side or a tablet either way up, three on a desktop-sized window — each row handed
 * the cover width its cell has room for, [videoThumbnailWidth].
 *
 * Measured from the width the grid actually gets rather than from the window class, so the rail,
 * a split screen or a resized desktop window all land on the layout that fits. One layout and one
 * scroll state however the window changes: turning a tablet keeps the same video at the top.
 *
 * Keyed by YouTube id, so a row keeps its place (and its state) across a content change rather
 * than its index.
 */
@Composable
fun VideoList(
    state: LazyGridState,
    videos: List<Video>,
    modifier: Modifier = Modifier,
    row: @Composable (video: Video, thumbnailWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = videoGridColumns(maxWidth)
        val thumbnailWidth = videoThumbnailWidth(maxWidth / columns)
        LazyVerticalGrid(
            columns = remember(columns) { GridCells.Fixed(columns) },
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.youtubeId }) { row(it, thumbnailWidth) }
        }
    }
}

/**
 * How many columns a grid [width] wide gets: the fewest that keep every cell within
 * [VIDEO_CELL_MAX_WIDTH], never so many that a cell drops under [VIDEO_CELL_MIN_WIDTH].
 *
 * Both bounds, because neither alone gives the layouts wanted. A minimum alone (what
 * `GridCells.Adaptive` offers) cannot put two columns in both a 720 dp grid (a tablet upright,
 * beside the rail) and a 1,200 dp one (the same tablet on its side): the first needs a minimum of
 * at most 360 dp, the second one over 400 dp, or it gets a third column of cells too narrow for a
 * big cover. The maximum picks the column count; the minimum only stops a width just past it —
 * 650 dp, say — from splitting into two cells narrower than a phone.
 */
internal fun videoGridColumns(width: Dp): Int {
    var columns = ceil(width / VIDEO_CELL_MAX_WIDTH).toInt().coerceAtLeast(1)
    while (columns > 1 && width / columns < VIDEO_CELL_MIN_WIDTH) columns--
    return columns
}

/**
 * The cover's width in a cell [cellWidth] wide: a fixed share of it, so the cover and the text
 * beside it grow together. 40 % is what a landscape tablet's 600 dp cells were given by hand
 * (240 dp); a phone's 360–430 dp single column gets 144–172 dp from the same rule.
 */
internal fun videoThumbnailWidth(cellWidth: Dp): Dp = cellWidth * VIDEO_THUMBNAIL_SHARE

/** Wider than this and a cell splits in two: 640 dp holds the biggest cover and a full title. */
internal val VIDEO_CELL_MAX_WIDTH = 640.dp

/** Narrower than this and a cell would read worse than a phone's single column. */
internal val VIDEO_CELL_MIN_WIDTH = 340.dp

private const val VIDEO_THUMBNAIL_SHARE = 0.4f
