package com.gmail.volkovskiyda.jellyshelf.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode

/**
 * The effective dark flag for a [ThemeMode]: forced by the user, or the system's own setting when
 * [ThemeMode.AUTO]. Kept next to the theme rather than inside it so [JellyshelfTheme] keeps taking
 * a plain boolean — previews and screenshot tests pass one directly and know nothing about modes.
 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.AUTO -> isSystemInDarkTheme()
}

/**
 * The colour [JellyshelfTheme] paints behind its content, resolved **outside** composition so the
 * activity can colour its window before the first frame is drawn — see
 * [MainActivity][com.gmail.volkovskiyda.jellyshelf.MainActivity].
 *
 * Deliberately mirrors the scheme choice below, dynamic colour included, so the start-up window
 * and the app that replaces it are the same colour and the hand-over is invisible. It assumes the
 * dynamic colour the app actually runs with; only host-side rendering opts out of that.
 */
fun themeBackgroundArgb(context: Context, darkTheme: Boolean): Int {
    val scheme =
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return scheme.background.toArgb()
}

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

@Composable
fun JellyshelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Remembered: a dynamic scheme is ~40 system-colour lookups into a new instance, and a
    // ColorScheme has no equals — so a fresh one is a changed `staticCompositionLocalOf` to
    // MaterialTheme, which turns skipping off for everything under it. The player wraps its whole
    // screen in this theme, and recomposes the call on every queue advance and PiP change. New
    // wallpaper colours arrive as a resources change the manifest's configChanges cannot claim, which
    // recreates the activity, so these keys cover everything that changes the scheme in place.
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        when {
            dynamicColor -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
