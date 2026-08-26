package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult

/**
 * The line a finished playlist creation reports.
 *
 * Composable, and shared by both lists: the resources live here rather than in either ViewModel,
 * which is what lets [VideoSelectionController] carry the result instead of a sentence and stay
 * testable as plain Kotlin. An error already carries wording written for the user — the repository
 * composes those — so it is passed through rather than re-wrapped.
 */
@Composable
internal fun playlistMessage(result: PlaylistResult): String = when (result) {
    is PlaylistResult.Success -> stringResource(
        R.string.playlist_created,
        result.name,
        pluralStringResource(R.plurals.video_count, result.count, result.count),
    )
    is PlaylistResult.Error -> result.message
}
