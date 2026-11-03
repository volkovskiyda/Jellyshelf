package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.R
import kotlin.math.roundToInt

/**
 * The row that says something is still playing, shown on every screen that is not the player.
 *
 * Stateless: it draws what it is given and reports taps. The state comes from
 * [NowPlayingState][com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingState], which the
 * playback service writes, so the bar is correct even on a launch that walked in on playback
 * already under way.
 *
 * [onOpen] is the whole row rather than a button, so the large target is the common action; the
 * three icon buttons carve their own targets out of it. Each of the four announces itself, since
 * none of them has a visible label — and the accessibility checks that run in every Compose test
 * fail on an unlabelled clickable.
 *
 * Next but not previous: at 48 dp a fourth icon target would take most of what is readable of the
 * title on a narrow phone, and previous is the rarer action, one tap away inside the player. It is
 * dimmed rather than hidden at the end of the queue so that [onStop]'s button never moves sideways
 * under a finger between videos.
 *
 * [progress] draws a line along the bottom edge, and **null draws nothing**: a video whose duration
 * the player has not reported yet has no honest fraction, and a zero-width line is indistinguishable
 * from one at the very start. A function rather than a value, called as the line draws: the
 * fraction moves once a second for as long as a video plays and nothing else here does, so a value
 * would recompose the whole bar on every tick. The line is the one idea worth taking from media3's
 * `MiniController`, which is otherwise rejected — that composable's own progress line takes a
 * `Player`, and this bar deliberately has none.
 */
@Composable
fun MiniPlayerBar(
    title: String?,
    artworkUri: String?,
    isPlaying: Boolean,
    hasNext: Boolean,
    progress: (() -> Float)?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            // Above the row rather than around it: the bar's job is to read as attached to
            // whatever list is above it, and a full border would read as a floating card.
            HorizontalDivider()
            Row(
                Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.mini_player_open),
                        onClick = onOpen,
                    )
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 64.dp, height = 36.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play,
                        ),
                    )
                }
                // No explicit tint, unlike PlayerControls' transportTint: that exists because the
                // player's buttons sit on raw video. Here IconButton is on surfaceContainer and
                // its own disabledContentColor already dims a disabled button.
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.next_video),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.mini_player_stop),
                    )
                }
            }
            // Below the row, so it reads as the bar's own bottom edge rather than as a control:
            // there is nothing to drag here, and the player screen one tap away is where scrubbing
            // belongs. Height is fixed rather than left to the default, which is thicker than a
            // 56 dp row can carry without looking like a second divider.
            if (progress != null) {
                ProgressLine(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PROGRESS_HEIGHT),
                )
            }
        }
    }
}

/**
 * The bar's progress line: a plain track with the played fraction over it, no cap, no gap and no
 * stop dot — `LinearProgressIndicator` draws all three for a standalone indicator, and at 2 dp
 * across a whole screen they read as a dashed line rather than as progress.
 *
 * Drawn by hand rather than through that component for one more reason: **it only redraws when the
 * line moves a pixel.** [progress] changes once a second for as long as a video plays, and every
 * change invalidates whatever read it while drawing. A redraw here is never just the line — Compose
 * repaints the whole window (1080 × 2340 on a mid-range phone, ~3 ms of RenderThread at normal
 * clocks and ~9.6 ms on the charger's, measured 2026-10-29) — yet one second of a 73-minute video is
 * a quarter of a pixel across a phone, so three ticks in four repainted an identical line. The draw
 * reads the fraction through a `derivedStateOf` that rounds it to the line's own pixel grid, and a
 * derived state only wakes its readers when its answer changes: a ten-minute video still redraws
 * on every tick (a second of it is more than a pixel), an hour-long one about every four seconds.
 *
 * The semantics read [progress] exactly, not the rounded value: TalkBack and the tests get the
 * fraction the service reported, as they would from the Material component.
 */
@Composable
private fun ProgressLine(progress: () -> Float, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableIntStateOf(0) }
    val stepped = remember(progress) {
        derivedStateOf {
            val fraction = progress().coerceIn(0f, 1f)
            val width = widthPx
            if (width <= 0) fraction else (fraction * width).roundToInt().toFloat() / width
        }
    }
    val color = MaterialTheme.colorScheme.primary
    val trackColor = ProgressIndicatorDefaults.linearTrackColor
    // Mirrored for right-to-left locales, as the Material indicator is.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress().coerceIn(0f, 1f), 0f..1f)
            }
            .drawBehind {
                drawRect(trackColor)
                val played = size.width * stepped.value
                drawRect(
                    color = color,
                    topLeft = Offset(if (rtl) size.width - played else 0f, 0f),
                    size = Size(played, size.height),
                )
            },
    )
}

private val PROGRESS_HEIGHT = 2.dp

private val BAR_HEIGHT = 56.dp
