package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey

/** One of the app's tabs: the screen it opens, and how the navigation names and draws it. */
data class TopLevelDestination(
    val key: AppNavKey,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** The three tabs, in display order. Library first: it is the stack root and the app's home. */
val topLevelDestinations = listOf(
    TopLevelDestination(AppNavKey.Library, R.string.tab_library, Icons.Filled.VideoLibrary),
    TopLevelDestination(AppNavKey.Categories, R.string.tab_categories, Icons.AutoMirrored.Filled.ViewList),
    TopLevelDestination(AppNavKey.Settings, R.string.tab_settings, Icons.Filled.Settings),
)

/**
 * Whether the tabs go down the start edge as a [NavigationRail] rather than along the bottom as a
 * [NavigationBar].
 *
 * Material's rule, taken as is: a compact window gets a bottom bar, anything wider gets a rail.
 * That covers every phone in landscape and a tablet either way up, which is the same set of
 * windows where a bottom bar spreads three items across most of a metre of screen and takes a
 * whole row of an already short viewport to do it. The breakpoint is [isMediumWidthOrWider]'s.
 */
@Composable
fun useNavigationRail(): Boolean = isMediumWidthOrWider()

/**
 * The tabs along the bottom of a compact window.
 *
 * @param enabled false while the tabs are faded out but still laid out — see the nav host, which
 *   keeps the space for the screen underneath. A disabled item takes no tap meant for that screen.
 */
@Composable
fun TopLevelNavigationBar(
    selected: AppNavKey?,
    enabled: Boolean,
    onSelect: (AppNavKey) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
    destinations: List<TopLevelDestination> = topLevelDestinations,
) {
    NavigationBar(modifier = modifier, windowInsets = windowInsets) {
        destinations.forEach { item ->
            NavigationBarItem(
                selected = selected == item.key,
                enabled = enabled,
                onClick = { onSelect(item.key) },
                // The visible label already names the item; a duplicate icon description would
                // make TalkBack announce it twice.
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

/**
 * The same tabs down the start edge of a wider window. Same items, same labels, same selection
 * and enabled rules as [TopLevelNavigationBar]; only where they sit differs, so a test that finds
 * a tab by its label finds it either way.
 *
 * Centred in the rail's height rather than stacked from its top, which is where the thumbs are on
 * a tablet held by its long edges, and which is where the bottom bar's items sat relative to the
 * hand before they moved here. Two weighted spacers, because `NavigationRail` has no arrangement
 * parameter of its own: its column already fills the height, so the spacers split what the items
 * leave over.
 */
@Composable
fun TopLevelNavigationRail(
    selected: AppNavKey?,
    enabled: Boolean,
    onSelect: (AppNavKey) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationRailDefaults.windowInsets,
    destinations: List<TopLevelDestination> = topLevelDestinations,
) {
    NavigationRail(modifier = modifier, windowInsets = windowInsets) {
        Spacer(Modifier.weight(1f))
        destinations.forEach { item ->
            NavigationRailItem(
                selected = selected == item.key,
                enabled = enabled,
                onClick = { onSelect(item.key) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
