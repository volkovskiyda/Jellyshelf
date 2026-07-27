package com.gmail.volkovskiyda.jellyshelf

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.ui.MainViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesScreen
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryVideosScreen
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailScreen
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryScreen
import com.gmail.volkovskiyda.jellyshelf.ui.rememberClickThrottle
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsScreen
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import com.gmail.volkovskiyda.jellyshelf.ui.theme.isDark
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

private data class TopLevel(val key: AppNavKey, val labelRes: Int, val icon: ImageVector)

// The scrims androidx applies to a three-button navigation bar, redeclared because
// SystemBarStyle.auto's defaults are internal. Only used when gesture navigation is off.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Covers the frames before composition; the effect below takes over once the persisted
        // theme mode is known, which is the only thing that can disagree with the system setting.
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = koinViewModel()
            val themeState by viewModel.themeState.collectAsStateWithLifecycle()
            // Auto while the setting is still loading — the same thing the window is already
            // showing, and JellyshelfApp renders no content until its own read lands.
            val darkTheme = (themeState?.mode ?: ThemeMode.AUTO).isDark()
            // enableEdgeToEdge decides bar-icon contrast from the *system* dark mode, so a user
            // who forces the app the other way would get white icons on white without this.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            JellyshelfTheme(darkTheme = darkTheme) {
                JellyshelfApp(viewModel)
            }
        }
    }
}

@Composable
internal fun JellyshelfApp(viewModel: MainViewModel = koinViewModel()) {
    val startStack by viewModel.startStack.collectAsStateWithLifecycle()

    // Render nothing until the start stack is resolved, so Library never flashes first.
    startStack?.let { JellyshelfNav(it, viewModel) }
}

@Composable
private fun JellyshelfNav(startStack: List<AppNavKey>, viewModel: MainViewModel) {
    val backStack = rememberNavBackStack(*startStack.toTypedArray())
    val saveableStateHolderDecorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()

    // Persist the stack on every change so the app reopens on the exact screen the user left.
    // The first emission is the just-restored stack, so re-saving it is a harmless no-op.
    LaunchedEffect(Unit) {
        snapshotFlow { backStack.filterIsInstance<AppNavKey>() }
            .collect { viewModel.saveBackStack(it) }
    }

    // Nav3 has no NavController.currentBackStack flow, but the back stack is a snapshot state
    // list, so snapshotFlow emits on every mutation — forward navigation, back, and tab switch.
    LaunchedEffect(Unit) {
        snapshotFlow { backStack.toList() }
            .collect { stack ->
                Timber.tag("Navigation").d("backStack (${stack.size}): ${stack.joinToString(" -> ")}")
            }
    }

    val topLevel = listOf(
        TopLevel(AppNavKey.Library, R.string.tab_library, Icons.Filled.VideoLibrary),
        TopLevel(AppNavKey.Categories, R.string.tab_categories, Icons.AutoMirrored.Filled.ViewList),
        TopLevel(AppNavKey.Settings, R.string.tab_settings, Icons.Filled.Settings),
    )
    val current = backStack.lastOrNull()
    val showBottomBar = topLevel.any { it.key == current }

    // Library is the app's home and always the stack root: switching to any other tab rebuilds the
    // stack as [Library, tab] so Back returns to Library, and one more Back exits.
    fun switchTo(key: AppNavKey) {
        if (backStack.lastOrNull() == key) return
        backStack.clear()
        backStack.add(AppNavKey.Library)
        if (key != AppNavKey.Library) backStack.add(key)
    }

    // NavDisplay requires a non-empty stack, so never pop the last (Library) entry — the system
    // back gesture then finishes the activity instead.
    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun push(key: AppNavKey) {
        // Belt-and-braces behind the throttle: two identical adjacent keys mean a duplicate
        // NavEntry contentKey, which collides in the saveable-state and ViewModel stores.
        if (backStack.lastOrNull() == key) return
        backStack.add(key)
    }

    // One throttle for every user-driven navigation on this screen: a double-tap landing on two
    // different rows, or on a row and then the back arrow, must not navigate twice either. The
    // system back gesture below stays unthrottled — pressing it twice quickly is deliberate.
    val navThrottle = rememberClickThrottle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { item ->
                        val label = stringResource(item.labelRes)
                        NavigationBarItem(
                            selected = current == item.key,
                            onClick = { switchTo(item.key) },
                            // The visible label already names the item; a duplicate icon
                            // description would make TalkBack announce it twice.
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavDisplay(
            backStack = backStack,
            // consumeWindowInsets keeps each screen's own TopAppBar from applying the status-bar
            // inset a second time on top of the scaffold padding; imePadding keeps the keyboard
            // from covering search fields and the lower Settings inputs.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
            entryDecorators = listOf(saveableStateHolderDecorator, viewModelStoreDecorator),
            onBack = { pop() },
        ) { key ->
            when (key) {
                is AppNavKey.Library -> NavEntry(key) {
                    LibraryScreen(
                        onVideoClick = { navThrottle { push(AppNavKey.Detail(it.youtubeId)) } },
                    )
                }

                is AppNavKey.Categories -> NavEntry(key) {
                    CategoriesScreen(onCategoryClick = { id, title ->
                        navThrottle { push(AppNavKey.CategoryVideos(id, title)) }
                    })
                }

                is AppNavKey.Settings -> NavEntry(key) {
                    SettingsScreen()
                }

                is AppNavKey.CategoryVideos -> NavEntry(key) {
                    CategoryVideosScreen(
                        categoryId = key.categoryId,
                        title = key.title,
                        onVideoClick = { navThrottle { push(AppNavKey.Detail(it.youtubeId)) } },
                        onBack = { navThrottle { pop() } },
                    )
                }

                is AppNavKey.Detail -> NavEntry(key) {
                    DetailScreen(
                        youtubeId = key.youtubeId,
                        onBack = { navThrottle { pop() } },
                    )
                }

                else -> throw IllegalArgumentException("Unknown key: $key")
            }
        }
    }
}
