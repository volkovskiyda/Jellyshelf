package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.ui.settings.ThemeModeSwitch

// Component-level goldens for the three-position theme switch. One per position, because the thumb
// and the icon tints are the whole point of the control and a screen-level golden only ever shows
// one of them.

private const val SWITCH_WIDTH = 140
private const val SWITCH_HEIGHT = 56

@PreviewTest
@Preview(widthDp = SWITCH_WIDTH, heightDp = SWITCH_HEIGHT, showBackground = true)
@Composable
private fun ThemeSwitchLight() {
    PreviewTheme {
        ThemeModeSwitch(mode = ThemeMode.LIGHT, onClick = {})
    }
}

@PreviewTest
@Preview(widthDp = SWITCH_WIDTH, heightDp = SWITCH_HEIGHT, showBackground = true)
@Composable
private fun ThemeSwitchAuto() {
    PreviewTheme {
        ThemeModeSwitch(mode = ThemeMode.AUTO, onClick = {})
    }
}

@PreviewTest
@Preview(widthDp = SWITCH_WIDTH, heightDp = SWITCH_HEIGHT, showBackground = true)
@Composable
private fun ThemeSwitchDark() {
    PreviewTheme {
        ThemeModeSwitch(mode = ThemeMode.DARK, onClick = {})
    }
}

/** The track and thumb take their colors from the scheme, so the dark palette gets its own golden. */
@PreviewTest
@Preview(widthDp = SWITCH_WIDTH, heightDp = SWITCH_HEIGHT, showBackground = true)
@Composable
private fun ThemeSwitchDarkTheme() {
    PreviewTheme(darkTheme = true) {
        ThemeModeSwitch(mode = ThemeMode.DARK, onClick = {})
    }
}
