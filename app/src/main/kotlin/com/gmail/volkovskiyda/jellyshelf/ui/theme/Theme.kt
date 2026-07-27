package com.gmail.volkovskiyda.jellyshelf.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import org.koin.compose.koinInject

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
fun themeBackgroundArgb(context: Context, darkTheme: Boolean, buildInfo: BuildInfo): Int {
    val scheme = when {
        buildInfo.isAtLeast(Build.VERSION_CODES.S) ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
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
    buildInfo: BuildInfo = koinInject(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && buildInfo.isAtLeast(Build.VERSION_CODES.S) -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
