package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.LocalAsyncImagePreviewHandler
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import java.time.ZoneOffset

/**
 * Theme wrapper for every screenshot preview. It pins the one thing the app's own default can't:
 * `dynamicColor = false`. Dynamic color is derived from the device wallpaper, so leaving it on
 * would make the reference images depend on the machine that generated them.
 *
 * It also installs [PreviewCoverHandler], so any image model a preview passes renders as a drawn
 * stand-in cover instead of a request to the network, and pins [LocalZoneId] to UTC so the upload
 * and sync stamps read the same on the machine that drew a golden and on the one checking it.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun PreviewTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAsyncImagePreviewHandler provides PreviewCoverHandler,
        LocalZoneId provides ZoneOffset.UTC,
    ) {
        JellyshelfTheme(
            darkTheme = darkTheme,
            dynamicColor = false,
            content = content,
        )
    }
}
