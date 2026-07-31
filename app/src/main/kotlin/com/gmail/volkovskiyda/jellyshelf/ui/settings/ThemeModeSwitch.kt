package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.ui.theme.LocalThemeRevealController

/** What the settings row and the switch's accessibility state call each mode. */
internal val ThemeMode.labelRes: Int
    @StringRes get() = when (this) {
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.AUTO -> R.string.theme_auto
        ThemeMode.DARK -> R.string.theme_dark
    }

private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.AUTO -> Icons.Filled.BrightnessAuto
        ThemeMode.DARK -> Icons.Filled.DarkMode
    }

// Three stations on a track sized like a stretched Material 3 Switch.
private val STATION_SIZE = 36.dp
private val TRACK_WIDTH = STATION_SIZE * ThemeMode.entries.size
private val THUMB_SIZE = 28.dp
private val ICON_SIZE = 18.dp

/** The Material minimum for anything a finger has to hit. */
private val MIN_TOUCH_TARGET = 48.dp

/** Centers the thumb within its station. */
private val THUMB_INSET = (STATION_SIZE - THUMB_SIZE) / 2

/**
 * A three-position theme switch: light, auto, dark, all visible at once, with a thumb resting on
 * the active one.
 *
 * Tapping anywhere on the control advances one position — the whole thing is a single click target
 * rather than three, because the sequence is a ping-pong (see
 * [ThemeState][com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState]) and a direct jump would
 * skip the direction it depends on. The thumb therefore only ever moves one station per tap, which
 * is what keeps the slide readable.
 */
@Composable
internal fun ThemeModeSwitch(
    mode: ThemeMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateDescription = stringResource(mode.labelRes)
    val thumbOffset by animateDpAsState(
        targetValue = STATION_SIZE * ThemeMode.entries.indexOf(mode),
        label = "themeThumbOffset",
    )
    // Absent in previews and in tests that compose the switch on its own, which is the whole point
    // of the local's null default: the switch keeps working, it just changes the theme without the
    // animation.
    val revealController = LocalThemeRevealController.current
    var centerInWindow by remember { mutableStateOf<Offset?>(null) }

    // The target, not the drawing. The pill is only STATION_SIZE tall, which is under the 48.dp
    // minimum a touch target has to meet — so the click and the semantics live out here, on a box
    // padded up to that minimum, and the pill is drawn inside at its designed size. Caught by the
    // accessibility checks in ThemeModeSwitchTest, which is exactly what they are there for.
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH_TARGET)
            .clickable(
                onClickLabel = stringResource(R.string.change_theme),
                // Not Role.Switch: this is a three-position control, and a tap advances one step
                // rather than toggling. Button is what a screen reader should announce.
                role = Role.Button,
            ) {
                // Armed before the mode change is even requested, because that request travels
                // through the ViewModel and DataStore before it comes back as a new theme — by then
                // the origin has to be waiting. A tap that changes nothing visible (light → auto
                // under a light system) leaves the arming to expire unused; see [ThemeRevealController].
                centerInWindow?.let { revealController?.armReveal(it) }
                onClick()
            }
            // The control as a whole reports which mode is selected, so TalkBack announces the
            // state once instead of reading three undifferentiated icons.
            .semantics { this.stateDescription = stateDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = TRACK_WIDTH, height = STATION_SIZE)
                // On the pill, not the target: the circular reveal grows from what the user sees.
                .onGloballyPositioned { centerInWindow = it.boundsInWindow().center }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    // Lambda overload: the animated offset is read in the layout phase, so a slide
                    // re-lays-out the thumb without recomposing the switch on every frame.
                    .offset { IntOffset(x = (thumbOffset + THUMB_INSET).roundToPx(), y = 0) }
                    .size(THUMB_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            // Drawn after the thumb so the active icon sits on top of it rather than under.
            Row {
                ThemeMode.entries.forEach { entry ->
                    val tint by animateColorAsState(
                        targetValue = if (entry == mode) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "themeIconTint",
                    )
                    Box(
                        modifier = Modifier.size(STATION_SIZE),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            // The control's state description already names the selected mode; a
                            // description per icon would have TalkBack read all three every time.
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(ICON_SIZE),
                        )
                    }
                }
            }
        }
    }
}
