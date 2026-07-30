// All of media3-ui-compose (PlayerSurface, rememberPresentationState, the button-state
// holders) is @UnstableApi, so the whole file opts in rather than annotating every function.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberSeekBackButtonState
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.playback.isDecodeFailure
import com.gmail.volkovskiyda.jellyshelf.util.currentChapter
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * In-app player: full-bleed video over black with hand-built Compose controls on the session's
 * [MediaController]. Back (or leaving any other way) deliberately does NOT stop playback —
 * audio continues in the background and the media notification is the way back to this screen.
 * Orientation is free (sensor); the surface just re-fits.
 */
@Composable
fun PlayerScreen(
    youtubeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlayerViewModel = koinViewModel { parametersOf(youtubeId) }
    val controller by viewModel.controller.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()

    RequestNotificationPermissionOnce()
    ImmersiveWhileHere()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        val c = controller
        if (c == null) {
            // Still connecting to the service. The back arrow stays reachable regardless.
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }
        } else {
            PlayerWithControls(controller = c, title = video?.title, chapters = chapters, onBack = onBack)
        }
    }
}

/** The connected player: surface, playback-state plumbing, and the tap-to-toggle overlay. */
@Composable
private fun PlayerWithControls(
    controller: MediaController,
    title: String?,
    chapters: List<Chapter>,
    onBack: () -> Unit,
) {
    // Snapshots the UI renders from — polled/listened, because a Player is not observable state.
    var positionMs by remember { mutableLongStateOf(controller.currentPosition.coerceAtLeast(0)) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var isBuffering by remember { mutableStateOf(controller.playbackState == Player.STATE_BUFFERING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var transcodingNotice by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        // The first decode failure isn't terminal — the service is already swapping in the HLS
        // transcode — so it gets an explanatory notice instead of the error toast. A decode
        // failure of the transcode itself (or any other error) surfaces for real.
        var decodeFailureSeen = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.isDecodeFailure() && !decodeFailureSeen) {
                    decodeFailureSeen = true
                    transcodingNotice = true
                } else {
                    errorMessage = error.message ?: error.errorCodeName
                }
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    // Playback errors surface like every other transient failure in the app: a Toast.
    val context = LocalContext.current
    errorMessage?.let { message ->
        val text = stringResource(R.string.playback_error, message)
        LaunchedEffect(message) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            errorMessage = null
        }
    }
    if (transcodingNotice) {
        val text = stringResource(R.string.playback_transcoding_fallback)
        LaunchedEffect(Unit) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            transcodingNotice = false
        }
    }

    // Poll position/duration while RESUMED; 500 ms is smooth enough for a seek bar and labels.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                positionMs = controller.currentPosition.coerceAtLeast(0)
                durationMs = controller.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                delay(POSITION_POLL_MS)
            }
        }
    }

    // Keep the screen awake exactly while playing; view state, so it must reset on dispose.
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    // Auto-hide while playing; pausing, scrubbing or the open chapter panel pins the controls.
    val controlsPinned = scrubbing || chaptersOpen
    LaunchedEffect(controlsVisible, isPlaying, controlsPinned) {
        if (controlsVisible && isPlaying && !controlsPinned) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    // The panel captures Back itself: closing it must not pop the whole player screen.
    BackHandler(enabled = chaptersOpen) { chaptersOpen = false }

    val playPause = rememberPlayPauseButtonState(controller)
    val seekBack = rememberSeekBackButtonState(controller)
    val seekForward = rememberSeekForwardButtonState(controller)
    val presentationState = rememberPresentationState(controller)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } },
    ) {
        PlayerSurface(
            player = controller,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentationState.videoSizeDp),
        )
        if (presentationState.coverSurface) {
            // Shutter until the first frame renders: solid black beats a stale/blank surface.
            Box(Modifier.matchParentSize().background(Color.Black))
        }
        if (isBuffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        if (controlsVisible) {
            PlayerControls(
                title = title,
                showPlay = playPause.showPlay,
                positionMs = positionMs,
                durationMs = durationMs,
                chapters = chapters,
                onPlayPause = playPause::onClick,
                onSeekBack = seekBack::onClick,
                onSeekForward = seekForward::onClick,
                onSeek = controller::seekTo,
                onScrubbingChanged = { scrubbing = it },
                onOpenChapters = { chaptersOpen = true },
                onBack = onBack,
            )
        }
        if (chaptersOpen) {
            ChaptersPanel(
                chapters = chapters,
                currentChapter = currentChapter(chapters, positionMs),
                onChapterClick = {
                    controller.seekTo(it.startMs)
                    chaptersOpen = false
                },
                onDismiss = { chaptersOpen = false },
            )
        }
    }
}

/**
 * The controls overlay, stateless so previews and tests can render it without a player: top bar
 * (back + title + chapters), centre transport row, bottom position–seek–duration bar with
 * chapter tick markers. Its only internal state is the in-flight scrub, reported via
 * [onScrubbingChanged] so the caller can pin the overlay open while the user drags.
 */
@Composable
internal fun PlayerControls(
    title: String?,
    showPlay: Boolean,
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onOpenChapters: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The scrim covers the full screen; the controls inside stay clear of the display cutout,
    // whose insets — unlike the hidden system bars' — never drop to zero on notched devices.
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }
            Text(
                title.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (chapters.isNotEmpty()) {
                IconButton(onClick = onOpenChapters) {
                    Icon(
                        Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = stringResource(R.string.chapters),
                        tint = Color.White,
                    )
                }
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSeekBack) {
                Icon(
                    Icons.Filled.Replay10,
                    contentDescription = stringResource(R.string.seek_back_10),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (showPlay) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(if (showPlay) R.string.play else R.string.pause),
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(onClick = onSeekForward) {
                Icon(
                    Icons.Filled.Forward10,
                    contentDescription = stringResource(R.string.seek_forward_10),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // While dragging, the labels and thumb show the scrub target; the seek fires on release.
        var scrubMs by remember { mutableStateOf<Long?>(null) }
        val shownMs = scrubMs ?: positionMs
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)) {
            // The chapter the shown position falls in — scrub-aware, so dragging previews the
            // chapter you'd land in, not the one still playing.
            currentChapter(chapters, shownMs)?.let { chapter ->
                Text(
                    chapter.title,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    formatPosition(shownMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = if (durationMs > 0) {
                        (shownMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = { fraction ->
                        if (durationMs > 0) {
                            if (scrubMs == null) onScrubbingChanged(true)
                            scrubMs = (fraction * durationMs).toLong()
                        }
                    },
                    onValueChangeFinished = {
                        scrubMs?.let(onSeek)
                        scrubMs = null
                        onScrubbingChanged(false)
                    },
                    modifier = Modifier.weight(1f).chapterTicks(chapters, durationMs),
                    enabled = durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Text(
                    formatDuration(durationMs / MILLIS_PER_SECOND),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Chapter tick marks over the slider: a short vertical bar at each chapter start's fraction of
 * the track. Drawn over the whole slider width — the M3 track spans it, the thumb just overlaps
 * the ends — which keeps this independent of the slider's internals. The 0:00 tick is skipped:
 * the track's start edge already marks it.
 */
private fun Modifier.chapterTicks(chapters: List<Chapter>, durationMs: Long): Modifier =
    drawWithContent {
        drawContent()
        if (durationMs <= 0) return@drawWithContent
        val half = CHAPTER_TICK_HEIGHT.toPx() / 2
        chapters.forEach { chapter ->
            if (chapter.startMs == 0L) return@forEach
            val x = (chapter.startMs.toFloat() / durationMs) * size.width
            val top = Offset(x, center.y - half)
            val bottom = Offset(x, center.y + half)
            // A white core in a dark halo, so the tick reads on the white played part of the
            // track and on the dim unplayed part alike.
            drawLine(
                color = Color.Black.copy(alpha = 0.7f),
                start = top,
                end = bottom,
                strokeWidth = CHAPTER_TICK_WIDTH.toPx() * 2,
            )
            drawLine(
                color = Color.White,
                start = top,
                end = bottom,
                strokeWidth = CHAPTER_TICK_WIDTH.toPx(),
            )
        }
    }

/**
 * The tappable chapter list over a full-screen scrim: timestamp + title per row, the current
 * chapter in the primary colour. Tapping outside (or Back, handled by the caller) dismisses.
 */
@Composable
internal fun ChaptersPanel(
    chapters: List<Chapter>,
    currentChapter: Chapter?,
    onChapterClick: (Chapter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = PANEL_SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            // Scrim and tap-to-dismiss span everything; the panel stays out of the cutout.
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = PANEL_MAX_HEIGHT)
                .background(Color.Black.copy(alpha = PANEL_BACKGROUND_ALPHA))
                // Swallow taps on the panel body so only the outside scrim dismisses.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Text(
                stringResource(R.string.chapters),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            LazyColumn {
                items(chapters, key = Chapter::startMs) { chapter ->
                    val highlight = chapter == currentChapter
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(chapter) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatPosition(chapter.startMs),
                            color = if (highlight) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.7f)
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            chapter.title,
                            color = if (highlight) MaterialTheme.colorScheme.primary else Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Immersive fullscreen for the lifetime of this screen: bars hidden, revealable with a swipe,
 * restored on leave. Window-level state, so the dispose branch is what keeps the rest of the
 * app's screens laid out normally.
 */
@Composable
private fun ImmersiveWhileHere() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = insets.systemBarsBehavior
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = previousBehavior
        }
    }
}

/**
 * One-shot POST_NOTIFICATIONS request (API 33+): the media notification is the only way back to
 * a backgrounded player. Denied → playback still works, the notification just stays hidden; the
 * system's own throttling decides whether the dialog ever shows again.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Position label: 0 is a real time here, unlike [formatDuration]'s "unknown" placeholder. */
private fun formatPosition(ms: Long): String =
    if (ms <= 0) "0:00" else formatDuration(ms / MILLIS_PER_SECOND)

private const val POSITION_POLL_MS = 500L
private const val CONTROLS_HIDE_DELAY_MS = 3_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val SCRIM_ALPHA = 0.4f
private const val PANEL_SCRIM_ALPHA = 0.6f
private const val PANEL_BACKGROUND_ALPHA = 0.92f
private val PANEL_MAX_HEIGHT = 360.dp
private val CHAPTER_TICK_HEIGHT = 8.dp
private val CHAPTER_TICK_WIDTH = 2.dp
