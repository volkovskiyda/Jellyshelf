package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.model.AnchorPosition
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.model.ScrollPosition
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.util.authorizedImageUrl
import com.gmail.volkovskiyda.jellyshelf.util.isJellyfinImageUrl
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import com.gmail.volkovskiyda.jellyshelf.util.formatUploadDate
import com.gmail.volkovskiyda.jellyshelf.util.watchedFraction
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/**
 * Resolves a stored thumbnail [url] to a loadable model. Stored URLs deliberately carry no
 * credentials, so Jellyfin-hosted images get the current api key appended at display time —
 * and while settings are still loading on cold start, Jellyfin URLs resolve to null instead
 * of firing a doomed unauthenticated request that would 401 and reload.
 */
@Composable
fun rememberThumbnailModel(url: String?): String? {
    val settings by koinInject<AppSettingsState>().settings.collectAsStateWithLifecycle()
    val loaded = settings
    return when {
        url == null -> null
        loaded == null -> url.takeUnless { isJellyfinImageUrl(it) }
        else -> authorizedImageUrl(url, loaded.serverUrl, loaded.apiKey)
    }
}

/**
 * A [LazyListState] whose scroll position is persisted under [key] via
 * [com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository].
 *
 * Restores the last position on tab switch and across app restart. `rememberSaveable` still
 * covers the fast list → detail → back and configuration-change cases in memory; the store
 * seeds the initial value whenever that saved state has been discarded.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberPersistedLazyListState(key: String): LazyListState {
    val store = koinInject<ScrollPositionRepository>()
    val state = rememberSaveable(key, saver = LazyListState.Saver) {
        val pos = store.peek(key)
        LazyListState(pos.index, pos.offset)
    }
    // Cold start: the store may not have seeded from disk when the state above was created —
    // peek() returns Zero rather than blocking the first frame on the disk read. Restore once
    // the seed lands, unless the list is still empty-of-content or the user already scrolled.
    var seedRestored by rememberSaveable(key) { mutableStateOf(store.isSeeded) }
    LaunchedEffect(key, state) {
        if (seedRestored) return@LaunchedEffect
        store.awaitSeeded()
        val pos = store.peek(key)
        if (pos != ScrollPosition.Zero) {
            // scrollToItem on a not-yet-loaded list would clamp to the top; wait for content.
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                state.scrollToItem(pos.index, pos.offset)
            }
        }
        seedRestored = true
    }
    LaunchedEffect(key, state) {
        snapshotFlow { ScrollPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
            .debounce(250)
            .distinctUntilChanged()
            .collect { store.save(key, it) }
    }
    DisposableEffect(key, state) {
        onDispose {
            store.save(key, ScrollPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset))
        }
    }
    return state
}

/**
 * A [LazyListState] whose scroll position is persisted under [key], anchored to the first
 * visible item's [anchorOf] value (its file name) rather than a raw index. On restore it
 * scrolls back to that exact item; if it is gone (removed or renamed), it scrolls to the
 * nearest item that sorts at or just above it. [items] must be sorted ascending by [anchorOf]
 * — the same order the list is displayed in.
 */
@OptIn(FlowPreview::class)
@Composable
fun <T> rememberAnchoredLazyListState(
    key: String,
    items: List<T>,
    anchorOf: (T) -> String,
): LazyListState {
    val store = koinInject<ScrollPositionRepository>()
    val state = rememberSaveable(key, saver = LazyListState.Saver) { LazyListState(0, 0) }
    val currentItems by rememberUpdatedState(items)
    val currentAnchorOf by rememberUpdatedState(anchorOf)
    // Guards against re-restoring (which would fight the user's own scrolling) once we've
    // either restored or confirmed there was nothing to restore.
    var restored by rememberSaveable(key) { mutableStateOf(false) }

    // Restore once, after the (async-loaded) list first becomes non-empty.
    LaunchedEffect(key, items) {
        if (restored || items.isEmpty()) return@LaunchedEffect
        val saved = store.peekAnchor(key)
        if (saved != null && saved.anchor.isNotEmpty()) {
            val index = items.floorIndexOfAnchor(saved.anchor, anchorOf)
            if (index >= 0) {
                val exact = anchorOf(items[index]) == saved.anchor
                state.scrollToItem(index, if (exact) saved.offset else 0)
            }
        }
        restored = true
    }

    // Persist the first visible item's anchor as the user scrolls.
    LaunchedEffect(key, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .debounce(250)
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (!restored) return@collect
                val item = currentItems.getOrNull(index) ?: return@collect
                store.saveAnchor(key, AnchorPosition(currentAnchorOf(item), offset))
            }
    }

    DisposableEffect(key, state) {
        onDispose {
            if (!restored) return@onDispose
            val item = currentItems.getOrNull(state.firstVisibleItemIndex) ?: return@onDispose
            store.saveAnchor(
                key,
                AnchorPosition(currentAnchorOf(item), state.firstVisibleItemScrollOffset),
            )
        }
    }
    return state
}

/**
 * Index of the last item whose anchor is `<=` [anchor] in a list sorted ascending by [anchorOf]
 * — i.e. the target item itself, or the nearest one just above it. Returns -1 if every item
 * sorts after [anchor] (restore to the top).
 */
private fun <T> List<T>.floorIndexOfAnchor(anchor: String, anchorOf: (T) -> String): Int {
    var result = -1
    for (i in indices) {
        if (anchorOf(this[i]) <= anchor) result = i else break
    }
    return result
}

@Composable
fun VideoRow(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AsyncImage(
                model = rememberThumbnailModel(video.thumbnailUrl),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            val fraction = watchedFraction(video.playbackPositionTicks, video.durationSeconds)
            if (fraction > 0f && !video.played) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.channel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildList {
                if (video.durationSeconds > 0) add(formatDuration(video.durationSeconds))
                formatUploadDate(video.uploadDate)?.let { add(it) }
            }.joinToString("  •  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (video.played) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.watched),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** Shown while a screen's first data emission is pending, so "loading" never reads as "empty". */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
