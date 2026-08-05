package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// One row per state that changes what the row draws: the watched check, the resume progress bar,
// and the missing-from-server marker. Dark is covered for the two states whose colours are
// theme-dependent in a way light can't reveal (the error-coloured marker, and the baseline).

@PreviewTest
@Preview(widthDp = 400, showBackground = true)
@Composable
private fun VideoRowDefault() {
    PreviewTheme { VideoRow(video = sampleVideo, onPlay = {}, onOpenDetails = {}, thumbnailModel = null) }
}

@PreviewTest
@Preview(widthDp = 400, showBackground = true)
@Composable
private fun VideoRowWatched() {
    PreviewTheme { VideoRow(video = watchedVideo, onPlay = {}, onOpenDetails = {}, thumbnailModel = null) }
}

@PreviewTest
@Preview(widthDp = 400, showBackground = true)
@Composable
private fun VideoRowPartWatched() {
    PreviewTheme { VideoRow(video = partWatchedVideo, onPlay = {}, onOpenDetails = {}, thumbnailModel = null) }
}

@PreviewTest
@Preview(widthDp = 400, showBackground = true)
@Composable
private fun VideoRowMissingFromServer() {
    PreviewTheme { VideoRow(video = missingVideo, onPlay = {}, onOpenDetails = {}, thumbnailModel = null) }
}

@PreviewTest
@Preview(widthDp = 400, showBackground = true)
@Composable
private fun VideoRowDefaultDark() {
    PreviewTheme(darkTheme = true) {
        VideoRow(video = sampleVideo, onPlay = {}, onOpenDetails = {}, thumbnailModel = null)
    }
}

@PreviewTest
@Preview(widthDp = 400, showBackground = true)
@Composable
private fun VideoRowMissingFromServerDark() {
    PreviewTheme(darkTheme = true) {
        VideoRow(video = missingVideo, onPlay = {}, onOpenDetails = {}, thumbnailModel = null)
    }
}
