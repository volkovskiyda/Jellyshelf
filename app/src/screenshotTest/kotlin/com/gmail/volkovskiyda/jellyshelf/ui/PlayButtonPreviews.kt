package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.ui.detail.PlayButton

// The split play button in each of the three sources it can be set to. One golden apiece, because
// the only thing that changes between them is the line under the word Play, and a full detail
// screen would bury that difference in a screenful of pixels that are identical every time.
//
// Every one of these renders the *chevron* too, which no device screenshot of this app can: the
// screenshots are shot in demo mode, where the other two sources are unreachable — nothing can
// hand a URL to another app or open a server that is not there — so the menu is not offered and
// the button is a plain pill. These are the only images that show the real, signed-in control.

/** The app's own player: the default, and the only source a demo install ever has. */
@PreviewTest
@Preview(showBackground = true)
@Composable
private fun PlayButtonInternal() {
    PreviewTheme { PlayButtonPreview(PlaybackMode.PLAY) }
}

/** Hands the video to whatever player the user picked, MX Player or VLC in practice. */
@PreviewTest
@Preview(showBackground = true)
@Composable
private fun PlayButtonExternal() {
    PreviewTheme { PlayButtonPreview(PlaybackMode.EXTERNAL) }
}

/** Opens the item in the Jellyfin server's own web player. */
@PreviewTest
@Preview(showBackground = true)
@Composable
private fun PlayButtonBrowser() {
    PreviewTheme { PlayButtonPreview(PlaybackMode.WEB) }
}

/** Dark, on the longest of the three labels — the one most likely to crowd the pill. */
@PreviewTest
@Preview(showBackground = true)
@Composable
private fun PlayButtonExternalDark() {
    PreviewTheme(darkTheme = true) { PlayButtonPreview(PlaybackMode.EXTERNAL) }
}

@Composable
private fun PlayButtonPreview(mode: PlaybackMode) {
    PlayButton(
        current = sampleVideo,
        settings = previewSettings.copy(playbackMode = mode),
        onPlay = { _, _, _ -> },
        onSelectMode = {},
    )
}
