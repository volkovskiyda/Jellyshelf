package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video

private const val ROW_PREVIEW_WIDTH = 400

// One row per state that changes what the row draws: the watched check, the resume progress bar,
// and the missing-from-server marker. Dark is covered for the two states whose colours are
// theme-dependent in a way light can't reveal (the error-coloured marker, and the baseline).

@PreviewTest
@Preview(widthDp = ROW_PREVIEW_WIDTH, showBackground = true)
@Composable
private fun VideoRowDefault() {
    PreviewTheme { PreviewVideoRow(sampleVideo) }
}

@PreviewTest
@Preview(widthDp = ROW_PREVIEW_WIDTH, showBackground = true)
@Composable
private fun VideoRowWatched() {
    PreviewTheme { PreviewVideoRow(watchedVideo) }
}

@PreviewTest
@Preview(widthDp = ROW_PREVIEW_WIDTH, showBackground = true)
@Composable
private fun VideoRowPartWatched() {
    PreviewTheme { PreviewVideoRow(partWatchedVideo) }
}

@PreviewTest
@Preview(widthDp = ROW_PREVIEW_WIDTH, showBackground = true)
@Composable
private fun VideoRowMissingFromServer() {
    PreviewTheme { PreviewVideoRow(missingVideo) }
}

@PreviewTest
@Preview(widthDp = ROW_PREVIEW_WIDTH, showBackground = true)
@Composable
private fun VideoRowDefaultDark() {
    PreviewTheme(darkTheme = true) {
        PreviewVideoRow(sampleVideo)
    }
}

@PreviewTest
@Preview(widthDp = ROW_PREVIEW_WIDTH, showBackground = true)
@Composable
private fun VideoRowMissingFromServerDark() {
    PreviewTheme(darkTheme = true) {
        PreviewVideoRow(missingVideo)
    }
}

/** A row with its stand-in cover, the one argument that changes from golden to golden. */
@Composable
private fun PreviewVideoRow(video: Video) {
    VideoRow(
        video = video,
        onPlay = {},
        onOpenDetails = {},
        thumbnailModel = previewThumbnail(video),
        // What a 400 dp single-column grid hands its rows, the width these previews render at.
        thumbnailWidth = videoThumbnailWidth(ROW_PREVIEW_WIDTH.dp),
    )
}
