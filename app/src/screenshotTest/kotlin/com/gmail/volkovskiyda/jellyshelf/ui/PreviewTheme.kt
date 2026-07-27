package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme

/**
 * Theme wrapper for every screenshot preview. Two things it pins that the app's own defaults
 * can't:
 *
 *  - `buildInfo`, which [JellyshelfTheme] normally takes from Koin — there is no Koin container
 *    under LayoutLib, so rendering the theme unwrapped fails with "KoinApplication has not been
 *    started". The parameter already exists for testability; previews just supply it.
 *  - `dynamicColor = false`. Dynamic color is derived from the device wallpaper, so leaving it on
 *    would make the reference images depend on the machine that generated them.
 */
@Composable
internal fun PreviewTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    JellyshelfTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        buildInfo = BuildInfo(isDebug = true, sdkInt = 36),
        content = content,
    )
}
