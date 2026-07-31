package com.gmail.volkovskiyda.jellyshelf

import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gmail.volkovskiyda.jellyshelf.data.repository.ThemeModeCache
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import com.gmail.volkovskiyda.jellyshelf.playback.PlaybackService
import com.gmail.volkovskiyda.jellyshelf.ui.MainViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesScreen
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryVideosScreen
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailScreen
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryScreen
import com.gmail.volkovskiyda.jellyshelf.ui.player.PlayerScreen
import com.gmail.volkovskiyda.jellyshelf.ui.rememberClickThrottle
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsScreen
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import com.gmail.volkovskiyda.jellyshelf.ui.theme.LocalThemeRevealController
import com.gmail.volkovskiyda.jellyshelf.ui.theme.ThemeReveal
import com.gmail.volkovskiyda.jellyshelf.ui.theme.ThemeRevealController
import com.gmail.volkovskiyda.jellyshelf.ui.theme.isDark
import com.gmail.volkovskiyda.jellyshelf.ui.theme.themeBackgroundArgb
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

private data class TopLevel(val key: AppNavKey, val labelRes: Int, val icon: ImageVector)

// The scrims androidx applies to a three-button navigation bar, redeclared because
// SystemBarStyle.auto's defaults are internal. Only used when gesture navigation is off.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    private val themeModeCache: ThemeModeCache by inject()
    private val buildInfo: BuildInfo by inject()

    // The same instance composition resolves via koinViewModel(): both come from this activity's
    // ViewModelStore. Held here so intent handling can reach it outside composition.
    private val mainViewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardOpenPlayer(intent)
        // Everything the window shows before composition — its colour and its bar icons — has to
        // be decided now, from the cache, because the persisted theme is still an async read away.
        val startupDark = cachedDarkTheme()
        window.setBackgroundDrawable(themeBackgroundArgb(this, startupDark, buildInfo).toDrawable())
        applyEdgeToEdge(startupDark)
        selectSplashTheme()
        setContent {
            val viewModel = mainViewModel
            val themeState by viewModel.themeState.collectAsStateWithLifecycle()
            // The cached mode until the real one lands: it is what the window is already painted
            // in, so agreeing with it keeps the hand-over invisible.
            val darkTarget = (themeState?.mode ?: themeModeCache.peek()).isDark()
            // Keeps the cache read by [cachedDarkTheme] honest for the next cold start.
            LaunchedEffect(themeState) {
                themeState?.let { themeModeCache.store(it.mode) }
            }

            // The switch reaches this through [LocalThemeRevealController] rather than through
            // SettingsActions: where a tap landed is presentation detail the settings screen has no
            // reason to carry.
            val revealController = remember { ThemeRevealController() }
            CompositionLocalProvider(LocalThemeRevealController provides revealController) {
                ThemeReveal(controller = revealController, darkTheme = darkTarget) { appliedDark ->
                    // Keyed on what is actually rendered rather than on the target: the bars and the
                    // window background have to flip in the same frame the snapshot overlay appears,
                    // not a frame earlier. (Bar *icons* are window state, not captured pixels, so
                    // they change at the start of a reveal rather than following its edge.)
                    DisposableEffect(appliedDark) {
                        window.setBackgroundDrawable(
                            themeBackgroundArgb(this@MainActivity, appliedDark, buildInfo)
                                .toDrawable(),
                        )
                        applyEdgeToEdge(appliedDark)
                        onDispose {}
                    }
                    JellyshelfTheme(darkTheme = appliedDark) {
                        JellyshelfApp(viewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardOpenPlayer(intent)
    }

    /**
     * A media-notification tap: [PlaybackService] put the playing video's youtubeId on the
     * session-activity intent; the nav collects the request and pushes the player screen.
     * Handles both the running-activity case (singleTop → onNewIntent) and a cold start.
     */
    private fun forwardOpenPlayer(intent: Intent?) {
        intent?.getStringExtra(PlaybackService.EXTRA_OPEN_PLAYER)
            ?.let(mainViewModel::requestOpenPlayer)
    }

    /**
     * The theme to paint with before the persisted one can be read.
     *
     * The XML theme is `DayNight`, so left alone the window follows the *system* — and start-up
     * takes about a second before anything paints over it, long enough to stare at a black window
     * while waiting for a light-forced app. The cache answers synchronously, which DataStore
     * cannot; it is stale only on the launch right after a mode change, which costs one
     * wrong-coloured start-up window before the composition above corrects it and the cache.
     */
    private fun cachedDarkTheme(): Boolean = when (themeModeCache.peek()) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> resources.configuration.isNightModeActive
    }

    /**
     * Picks the splash theme for the **next** launch, which is the only part of start-up the app
     * cannot paint itself: the system builds the splash before the process exists, from the
     * manifest's DayNight theme, so a light-forced app is preceded by a black splash.
     *
     * The platform persists this choice, and [Resources.ID_NULL] resets it — which is what lets
     * [ThemeMode.AUTO] hand the decision back to DayNight, where it is already correct. Being a
     * launch behind only shows on the first start after a mode change, the same as
     * [ThemeModeCache].
     */
    private fun selectSplashTheme() {
        if (!buildInfo.isAtLeast(Build.VERSION_CODES.S)) return
        splashScreen.setSplashScreenTheme(
            when (themeModeCache.peek()) {
                ThemeMode.LIGHT -> R.style.Theme_Jellyshelf_Splash_Light
                ThemeMode.DARK -> R.style.Theme_Jellyshelf_Splash_Dark
                ThemeMode.AUTO -> Resources.ID_NULL
            },
        )
    }

    /**
     * `enableEdgeToEdge` decides bar-icon contrast from the *system* dark mode, so a user who
     * forces the app the other way gets white icons on white without this.
     */
    private fun applyEdgeToEdge(darkTheme: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
        )
    }
}

@Composable
internal fun JellyshelfApp(viewModel: MainViewModel = koinViewModel()) {
    val startStack by viewModel.startStack.collectAsStateWithLifecycle()

    // Render nothing until the start stack is resolved, so Library never flashes first.
    startStack?.let { JellyshelfNav(it, viewModel) }
}

@Composable
@Suppress("SpreadOperator") // rememberNavBackStack is vararg-only; copies a handful of nav keys, once per composition
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

    // A media-notification tap opens the player for the id it carried. Runs only once the nav
    // exists, which is what makes the cold-start tap work: startStack has already resolved.
    val openPlayer by viewModel.openPlayer.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayer) {
        openPlayer?.let { id ->
            // Compare on the video, not the whole key: a player opened from a list carries an
            // origin the notification knows nothing about, and re-pushing it would stack two
            // player entries for the same video.
            val top = backStack.lastOrNull()
            val alreadyOpen = top is AppNavKey.Player && top.youtubeId == id
            if (!alreadyOpen) backStack.add(AppNavKey.Player(id))
            viewModel.consumeOpenPlayer()
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
                        onVideoClick = {
                            // The origin rides on Detail so that Play, one screen later, still
                            // knows which list the user was in — Detail itself never reads it.
                            navThrottle { push(AppNavKey.Detail(it.youtubeId, PlayerOrigin.Library)) }
                        },
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
                        onVideoClick = {
                            val origin = PlayerOrigin.Category(key.categoryId)
                            navThrottle { push(AppNavKey.Detail(it.youtubeId, origin)) }
                        },
                        onBack = { navThrottle { pop() } },
                    )
                }

                is AppNavKey.Detail -> NavEntry(key) {
                    DetailScreen(
                        youtubeId = key.youtubeId,
                        onBack = { navThrottle { pop() } },
                        onPlayInApp = { id -> navThrottle { push(AppNavKey.Player(id, key.origin)) } },
                    )
                }

                is AppNavKey.Player -> NavEntry(key) {
                    PlayerScreen(
                        youtubeId = key.youtubeId,
                        origin = key.origin,
                        onBack = { navThrottle { pop() } },
                    )
                }

                else -> throw IllegalArgumentException("Unknown key: $key")
            }
        }
    }
}
