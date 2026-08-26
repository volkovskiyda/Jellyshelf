package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.R

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
 */
@Composable
fun MiniPlayerBar(
    title: String?,
    artworkUri: String?,
    isPlaying: Boolean,
    hasNext: Boolean,
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
        }
    }
}

private val BAR_HEIGHT = 56.dp
