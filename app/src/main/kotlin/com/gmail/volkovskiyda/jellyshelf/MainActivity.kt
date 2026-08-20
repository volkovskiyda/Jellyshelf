package com.gmail.volkovskiyda.jellyshelf

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gmail.volkovskiyda.jellyshelf.data.repository.ThemeModeCache
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingState
import com.gmail.volkovskiyda.jellyshelf.playback.PipAspect
import com.gmail.volkovskiyda.jellyshelf.playback.PlaybackService
import com.gmail.volkovskiyda.jellyshelf.playback.pipAspect
import com.gmail.volkovskiyda.jellyshelf.playback.pipEligible
import com.gmail.volkovskiyda.jellyshelf.ui.InstallProgressEffect
import com.gmail.volkovskiyda.jellyshelf.ui.InstallSnackbarHost
import com.gmail.volkovskiyda.jellyshelf.ui.MainViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.MiniPlayerBar
import com.gmail.volkovskiyda.jellyshelf.ui.UpdateDialog
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
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.UpdateNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import timber.log.Timber

private data class TopLevel(val key: AppNavKey, val labelRes: Int, val icon: ImageVector)

// The scrims androidx applies to a three-button navigation bar, redeclared because
// SystemBarStyle.auto's defaults are internal. Only used when gesture navigation is off.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Whether the activity is showing as a Picture-in-Picture window right now.
 *
 * A composition local rather than a parameter threaded through the nav: only the player screen
 * cares, it sits several layers down, and everything that renders that screen's parts outside the
 * app — previews, screenshot tests, the Compose behaviour suite — is correct with the default.
 */
val LocalIsInPip = compositionLocalOf { false }

class MainActivity : ComponentActivity() {
    private val themeModeCache: ThemeModeCache by inject()

    // The same instance composition resolves via koinViewModel(): both come from this activity's
    // ViewModelStore. Held here so intent handling can reach it outside composition.
    private val mainViewModel: MainViewModel by viewModel()

    /** Reached from intent handling, outside composition — hence the field rather than koinInject. */
    private val updateChecker: UpdateChecker by inject()

    /** Fed by the platform's own callback, read by composition — see [LocalIsInPip]. */
    private val inPip = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardOpenPlayer(intent)
        forwardShowUpdate(intent)
        // Everything the window shows before composition — its colour and its bar icons — has to
        // be decided now, from the cache, because the persisted theme is still an async read away.
        val startupDark = cachedDarkTheme()
        window.setBackgroundDrawable(themeBackgroundArgb(this, startupDark).toDrawable())
        applyEdgeToEdge(startupDark)
        selectSplashTheme()
        addOnPictureInPictureModeChangedListener { inPip.value = it.isInPictureInPictureMode }
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
            val isInPip by inPip.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalThemeRevealController provides revealController,
                LocalIsInPip provides isInPip,
            ) {
                ThemeReveal(controller = revealController, darkTheme = darkTarget) { appliedDark ->
                    // Keyed on what is actually rendered rather than on the target: the bars and the
                    // window background have to flip in the same frame the snapshot overlay appears,
                    // not a frame earlier. (Bar *icons* are window state, not captured pixels, so
                    // they change at the start of a reveal rather than following its edge.)
                    DisposableEffect(appliedDark) {
                        window.setBackgroundDrawable(
                            themeBackgroundArgb(this@MainActivity, appliedDark).toDrawable(),
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
        forwardShowUpdate(intent)
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
     * Keeps the window's Picture-in-Picture parameters in step with what is on screen.
     *
     * `setAutoEnterEnabled` is the whole mechanism: rather than catching the moment the user
     * leaves and calling `enterPictureInPictureMode` — which only ever sees the button-navigation
     * case and misses the gesture — the activity states *in advance* whether leaving now should
     * shrink it, and the platform does the rest. That is why this has to be re-stated whenever
     * the answer changes rather than called once.
     *
     * The aspect ratio is left unset while the size is unknown, so the platform picks its own
     * instead of being handed a degenerate one.
     */
    fun updatePipParams(eligible: Boolean, aspect: PipAspect?) {
        val params = PictureInPictureParams.Builder()
            .setAutoEnterEnabled(eligible)
            .apply { aspect?.let { setAspectRatio(Rational(it.numerator, it.denominator)) } }
            .build()
        setPictureInPictureParams(params)
    }

    /**
     * An update-notification tap: show the offer wherever the app lands, rather than making the
     * user find the Library tab to read the answer to something they just asked for by tapping.
     *
     * Only promotes an offer that is already in hand. A tap can easily outlive the process that
     * found the update — the notification is the surface for exactly that user — and in that case
     * the cold-start check running a few lines above finds it again, which is why this needs no
     * fallback of its own.
     */
    private fun forwardShowUpdate(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotification.EXTRA_SHOW_UPDATE, false) != true) return
        updateChecker.showRequested()
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
@OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId, on the Scaffold below
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

    // The update offer, hosted here because this is the only place that knows which tab is current.
    // The tab is the whole suppression rule for an offer nobody asked for: the player, detail and
    // the other tabs are excluded by construction rather than by a list of exceptions that a new
    // screen could forget to join. An offer the user *did* ask for skips that rule entirely — it
    // is the answer to a question posed on the Settings tab, and belongs where it was asked.
    val updateChecker: UpdateChecker = koinInject()
    val update by updateChecker.available.collectAsStateWithLifecycle()
    val updateScope = rememberCoroutineScope()
    val context = LocalContext.current
    update?.takeIf { it.requested || current == AppNavKey.Library }?.let { offer ->
        val info = offer.info
        // Stamps the once-a-day floor when the dialog is actually seen, not when the check found
        // something: checking from Settings and never coming back to Library is not an
        // interruption. Keyed on the version code, so a genuinely new build re-stamps while a
        // recomposition does not. A requested offer stamps nothing at all — see markDialogShown.
        LaunchedEffect(info.versionCode) { updateChecker.markDialogShown(offer) }
        UpdateDialog(
            info = info,
            onUpdate = {
                // Not a dismissal: if the install fails or the browser download is abandoned, the
                // prompt should return on the next check rather than be snoozed for a week.
                updateChecker.clearAvailable()
                if (updateChecker.canInstall(info)) {
                    updateScope.launch { updateChecker.install(info) }
                } else {
                    // A GitHub release with no published digest: there is nothing to check the
                    // download against, so it is not installed in-app at all. The browser takes it
                    // from here, exactly as every GitHub update did before.
                    Playback.openUrl(context, info.downloadUrl)
                }
            },
            onDismiss = { updateScope.launch { updateChecker.dismiss(info) } },
        )
    }

    // The install narrates itself app-wide: the dialog above closes the moment "Update" is tapped,
    // and the download outlives whatever screen the user moves to next.
    val installState by updateChecker.installState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    InstallProgressEffect(
        state = installState,
        hostState = snackbarHostState,
        onFailureDismissed = updateChecker::clearInstallState,
    )

    // Something is playing and this is not the player screen: the bar is the way back to it, and
    // (with back-stopping playback) the only in-app way to end the session. Written by
    // PlaybackService, so it is already right on a launch that walked in on playback under way.
    val nowPlayingState: NowPlayingState = koinInject()
    val nowPlaying by nowPlayingState.nowPlaying.collectAsStateWithLifecycle()
    val showMiniPlayer = nowPlaying != null && current !is AppNavKey.Player

    // Picture-in-Picture is stated in advance, not triggered — see MainActivity.updatePipParams.
    // Re-stated whenever the answer changes: which screen is on top, whether it is playing, and
    // the shape of the video once the decoder reports it.
    val activity = LocalActivity.current as? MainActivity
    val pipEligible = pipEligible(current, nowPlaying)
    val pipAspect = nowPlaying?.let { pipAspect(it.videoWidth, it.videoHeight) }
    LaunchedEffect(activity, pipEligible, pipAspect) {
        activity?.updatePipParams(pipEligible, pipAspect)
    }

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
        modifier = Modifier
            .fillMaxSize()
            // Republishes every Compose testTag below this point as an Android resource id. It
            // changes nothing for the app or for the Compose test suite, which match on semantics
            // directly; it exists for the baseline-profile generator, which drives the app through
            // UiAutomator. UiAutomator cannot see test tags at all without this, so By.res(...)
            // would match nothing and quietly profile the launch and nothing else.
            .semantics { testTagsAsResourceId = true },
        // The host reads installState too, not just the host state: that is what lets the download
        // percentage climb inside one snackbar instead of animating a new one in per tick.
        snackbarHost = { InstallSnackbarHost(snackbarHostState, installState) },
        bottomBar = {
            // One slot, two things that can be in it. The bar sits above the tabs when both show;
            // on Detail and CategoryVideos there are no tabs, and then the bar is what has to
            // clear the system navigation — otherwise it draws under the gesture pill.
            Column {
                nowPlaying?.takeIf { showMiniPlayer }?.let { playing ->
                    MiniPlayerBar(
                        title = playing.title,
                        artworkUri = playing.artworkUri,
                        isPlaying = playing.isPlaying,
                        // Same dedupe the notification path uses: compare on the video, since a
                        // player opened from a list carries an origin the bar knows nothing about
                        // and re-pushing it would stack two player entries for one video.
                        onOpen = { navThrottle { push(AppNavKey.Player(playing.youtubeId)) } },
                        onPlayPause = nowPlayingState::playPause,
                        onStop = nowPlayingState::stop,
                        // The horizontal inset always, the bottom one only when the bar is the
                        // lowest thing in the slot. In landscape with three-button navigation the
                        // system bar is down one *side*, and without the horizontal inset the
                        // bar's stop button draws underneath it — NavigationBar insets itself, so
                        // only the bar was exposed. When the tabs are showing they own the bottom
                        // inset, and taking it here as well would leave a gap between the two.
                        modifier = Modifier.windowInsetsPadding(
                            if (showBottomBar) {
                                WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                            } else {
                                WindowInsets.navigationBars
                            },
                        ),
                    )
                }
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
                        // A thumbnail tap goes straight to the player, carrying the same origin the
                        // detour through Detail would have handed it: the queue is the library as
                        // the user has it narrowed, either way in.
                        onPlayVideo = {
                            navThrottle { push(AppNavKey.Player(it.youtubeId, PlayerOrigin.Library)) }
                        },
                        onOpenDetails = {
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
                    SettingsScreen(
                        // A seeded demo goes straight to the library it just filled. switchTo
                        // replaces the stack rather than pushing, so Back exits from Library
                        // instead of walking back into the Settings screen that started it.
                        onDemoEntered = { switchTo(AppNavKey.Library) },
                    )
                }

                is AppNavKey.CategoryVideos -> NavEntry(key) {
                    val origin = PlayerOrigin.Category(key.categoryId)
                    CategoryVideosScreen(
                        categoryId = key.categoryId,
                        title = key.title,
                        onPlayVideo = {
                            navThrottle { push(AppNavKey.Player(it.youtubeId, origin)) }
                        },
                        onOpenDetails = {
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
