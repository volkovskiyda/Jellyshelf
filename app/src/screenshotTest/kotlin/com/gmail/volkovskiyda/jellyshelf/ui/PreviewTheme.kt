package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme

/**
 * Theme wrapper for every screenshot preview. It pins the one thing the app's own default can't:
 * `dynamicColor = false`. Dynamic color is derived from the device wallpaper, so leaving it on
 * would make the reference images depend on the machine that generated them.
 */
@Composable
internal fun PreviewTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    JellyshelfTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        content = content,
    )
}
