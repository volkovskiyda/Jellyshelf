// All of media3-ui-compose (PlayerSurface, rememberPresentationState, the button-state
// holders) is @UnstableApi, so the whole file opts in rather than annotating every function.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberSeekBackButtonState
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import com.gmail.volkovskiyda.jellyshelf.playback.isDecodeFailure
import com.gmail.volkovskiyda.jellyshelf.ui.BackButton
import com.gmail.volkovskiyda.jellyshelf.util.currentChapter
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.NumberFormat

/**
 * The elapsed-position label, tagged so the baseline-profile generator can read it: it is the only
 * thing on this screen that says whether the *stream* is rolling. The play/pause icon follows the
 * play/pause intent and flips before a byte arrives, and the seek bar carries no text at all —
 * `docs/BASELINE-PROFILE.md` has the whole story. Published as an Android resource id by
 * `testTagsAsResourceId` on `MainActivity`'s root Scaffold, which is what makes it visible to
 * UiAutomator.
 */
internal const val PLAYER_POSITION_TAG = "player_position"

/**
 * In-app player: full-bleed video over black with hand-built Compose controls on the session's
 * [MediaController]. Leaving the screen on purpose — any back arrow or system back — stops
 * playback, reports the position to the server and drops the media notification: back means done
 * watching. Merely hiding the app keeps playing, and the notification is the way back to here.
 * Orientation is free (sensor); the surface just re-fits.
 */
@Composable
fun PlayerScreen(
    youtubeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    origin: PlayerOrigin = PlayerOrigin.None,
) {
    val viewModel: PlayerViewModel = koinViewModel { parametersOf(AppNavKey.Player(youtubeId, origin)) }
    val controller by viewModel.controller.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()

    ImmersiveWhileHere()

    // Every explicit exit routes through here — and only explicit exits, which is why it is not
    // an onCleared()/lifecycle hook: those also fire on rotation and on minimizing.
    val leave = {
        viewModel.stopPlayback()
        onBack()
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        val c = controller
        if (c == null) {
            // Still connecting to the service. The back arrow stays reachable regardless — with
            // no controller yet there is nothing to stop, so this is a plain leave.
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            BackButton(
                onClick = leave,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(4.dp),
                tint = Color.White,
            )
        } else {
            PlayerWithControls(
                controller = c,
                title = video?.title,
                chapters = chapters,
                onSpeedPicked = viewModel::savePlaybackSpeed,
                onBack = leave,
            )
        }
    }
}

/** The connected player: surface, playback-state plumbing, and the tap-to-toggle overlay. */
@Composable
private fun PlayerWithControls(
    controller: MediaController,
    title: String?,
    chapters: List<Chapter>,
    onSpeedPicked: (Float) -> Unit,
    onBack: () -> Unit,
) {
    // Snapshots the UI renders from — polled/listened, because a Player is not observable state.
    var positionMs by remember { mutableLongStateOf(controller.currentPosition.coerceAtLeast(0)) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var isBuffering by remember { mutableStateOf(controller.playbackState == Player.STATE_BUFFERING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var transcodingNotice by remember { mutableStateOf(false) }
    // Where in the queue we are. Only the ends matter to the UI, and they move whenever the queue
    // advances, so they are listened for rather than polled with the position.
    var hasPrevious by remember { mutableStateOf(controller.hasPreviousMediaItem()) }
    var hasNext by remember { mutableStateOf(controller.hasNextMediaItem()) }

    DisposableEffect(controller) {
        // The first decode failure isn't terminal — the service is already swapping in the HLS
        // transcode — so it gets an explanatory notice instead of the error toast. A decode
        // failure of the transcode itself (or any other error) surfaces for real.
        var decodeFailureSeen = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                hasPrevious = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // The queue itself arrives asynchronously — the session resolves every id before
                // the timeline exists, so the first read above is of an empty one.
                hasPrevious = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
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
    var speedMenuOpen by remember { mutableStateOf(false) }
    // Auto-hide while playing; scrubbing, the open chapter panel or the speed menu pins the
    // controls (hiding them would tear the open menu out of the composition mid-use).
    val controlsPinned = scrubbing || chaptersOpen || speedMenuOpen
    LaunchedEffect(controlsVisible, isPlaying, controlsPinned) {
        if (controlsVisible && isPlaying && !controlsPinned) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    // One handler for both meanings of Back, so their priority is in the code rather than in the
    // dispatcher's registration order: an open panel consumes the press (closing it must not pop
    // the screen), and otherwise Back is the explicit leave that stops playback.
    BackHandler { if (chaptersOpen) chaptersOpen = false else onBack() }

    val playPause = rememberPlayPauseButtonState(controller)
    val seekBack = rememberSeekBackButtonState(controller)
    val seekForward = rememberSeekForwardButtonState(controller)
    val playbackSpeed = rememberPlaybackSpeedState(controller)
    val presentationState = rememberPresentationState(controller)

    // The surface's one drag gesture: horizontal scrubbing (see PlayerGestureHandler). The pill
    // lingers briefly after the finger lifts, then hides.
    var gestureIndicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var gestureActive by remember { mutableStateOf(false) }
    val gestureHandler = remember(controller) {
        PlayerGestureHandler(object : PlayerGestureHandler.Host {
            override fun canSeek() = controller.isCurrentMediaItemSeekable && controller.duration > 0

            override fun seekStartMs() = controller.currentPosition.coerceAtLeast(0)

            override fun seekDurationMs() = controller.duration.coerceAtLeast(0)

            override fun onSeekPreview(targetMs: Long, deltaMs: Long) {
                gestureActive = true
                gestureIndicator = GestureIndicator.Seek(targetMs, deltaMs)
            }

            override fun onSeekCommit(targetMs: Long) {
                controller.seekTo(targetMs)
            }

            override fun onGestureEnd() {
                gestureActive = false
            }
        })
    }
    LaunchedEffect(gestureActive, gestureIndicator) {
        if (!gestureActive && gestureIndicator != null) {
            delay(INDICATOR_LINGER_MS)
            gestureIndicator = null
        }
    }

    // Press-and-hold forces 3× until the finger lifts. The speed to go back to is read off the
    // controller at press time, not assumed to be 1× — a hold started at 1.5× returns to 1.5×.
    var speedBeforeHold by remember { mutableStateOf<Float?>(null) }
    // The release half runs in the pointer handler below, which only exists while this composition
    // does — so a hold interrupted by the composable going away (rotating with the finger still
    // down recreates the activity) would leave the *session* at 3× with nothing holding it there.
    // The player outlives this screen, so undoing the hold has to be tied to the screen's lifetime
    // as well as to the finger's.
    DisposableEffect(controller) {
        onDispose { speedBeforeHold?.let(controller::setPlaybackSpeed) }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Both halves of the hold live in one pointerInput, keyed on the controller the
            // button states are themselves remembered from, so neither can restart mid-gesture.
            .pointerInput(controller) {
                coroutineScope {
                    launch {
                        detectTapGestures(
                            // Which half was tapped picks the direction; the increments come from
                            // the player, so a double-tap and the buttons cannot disagree.
                            onDoubleTap = { offset ->
                                if (offset.x < size.width / 2) {
                                    seekBack.onClick()
                                } else {
                                    seekForward.onClick()
                                }
                            },
                            onLongPress = {
                                speedBeforeHold = controller.playbackParameters.speed
                                controller.setPlaybackSpeed(HOLD_SPEED)
                                gestureActive = true
                                gestureIndicator = GestureIndicator.Speed(HOLD_SPEED)
                            },
                            onTap = { controlsVisible = !controlsVisible },
                        )
                    }
                    // onLongPress has no release half; this supplies it.
                    launch {
                        awaitGestureReleases {
                            val restore = speedBeforeHold
                            if (restore != null) {
                                speedBeforeHold = null
                                controller.setPlaybackSpeed(restore)
                                gestureActive = false
                            }
                        }
                    }
                }
            }
            .playerDragGestures(gestureHandler),
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
                speed = playbackSpeed.playbackSpeed,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onPlayPause = playPause::onClick,
                onSeekBack = seekBack::onClick,
                onSeekForward = seekForward::onClick,
                // The explicit *MediaItem* variants: seekToPrevious() would restart the current
                // video once past its threshold, which is a different button entirely.
                onPrevious = controller::seekToPreviousMediaItem,
                onNext = controller::seekToNextMediaItem,
                onSeek = controller::seekTo,
                onSetSpeed = { speed ->
                    playbackSpeed.updatePlaybackSpeed(speed)
                    // Only a menu pick is a choice worth keeping. Press-and-hold's 3× goes
                    // straight to the controller in the gesture handler above, and is never saved.
                    onSpeedPicked(speed)
                },
                onScrubbingChanged = { scrubbing = it },
                onSpeedMenuChanged = { speedMenuOpen = it },
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
        gestureIndicator?.let { indicator ->
            GestureIndicatorPill(
                indicator,
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(top = 48.dp),
            )
        }
    }
}

/**
 * The controls overlay, stateless so previews and tests can render it without a player: top bar
 * (back + title + speed menu + chapters), centre transport row, bottom chapter-step row and
 * position–seek–duration bar with chapter tick markers. Its only internal state is transient
 * interaction — the in-flight scrub and the open speed menu — each reported via its `on*Changed`
 * callback so the caller can pin the overlay open while the user is mid-gesture.
 */
@Composable
@Suppress("LongParameterList") // A stateless overlay: every control it renders is one more pair.
internal fun PlayerControls(
    title: String?,
    showPlay: Boolean,
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    speed: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onSpeedMenuChanged: (Boolean) -> Unit,
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
            BackButton(onClick = onBack, tint = Color.White)
            Text(
                title.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SpeedMenuButton(speed = speed, onSetSpeed = onSetSpeed, onMenuChanged = onSpeedMenuChanged)
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

        // Five buttons at the old 40.dp spacing overflow a portrait phone (the play button alone
        // is 72.dp and the rest carry 48.dp touch targets), so the gaps shrink rather than the
        // targets.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous_video),
                    tint = transportTint(hasPrevious),
                    modifier = Modifier.size(36.dp),
                )
            }
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
                    Icons.Filled.Forward30,
                    contentDescription = stringResource(R.string.seek_forward_30),
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next_video),
                    tint = transportTint(hasNext),
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        // While dragging, the labels and thumb show the scrub target; the seek fires on release.
        var scrubMs by remember { mutableStateOf<Long?>(null) }
        val shownMs = scrubMs ?: positionMs
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)) {
            if (chapters.isNotEmpty()) ChapterStepRow(chapters, shownMs, onSeek)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    formatPosition(shownMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(PLAYER_POSITION_TAG),
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
 * The current chapter's name with a step arrow on either side, above the seek bar.
 *
 * Chapter stepping lives here rather than in the transport row or on a long-press of the seek
 * buttons: the transport row is already five buttons wide on a portrait phone, and a long-press
 * would be invisible to anyone who did not go looking. Next to the chapter name the arrows
 * explain themselves, and the row is already conditional on the video having chapters at all —
 * which most do not.
 *
 * The name follows the scrubbed position, so dragging previews the chapter you would land in
 * rather than the one still playing.
 */
@Composable
private fun ChapterStepRow(chapters: List<Chapter>, shownMs: Long, onSeek: (Long) -> Unit) {
    val previousMs = previousChapterStartMs(chapters, shownMs)
    val nextMs = nextChapterStartMs(chapters, shownMs)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { previousMs?.let(onSeek) }, enabled = previousMs != null) {
            Icon(
                Icons.Filled.FastRewind,
                contentDescription = stringResource(R.string.previous_chapter),
                tint = transportTint(previousMs != null),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            currentChapter(chapters, shownMs)?.title.orEmpty(),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { nextMs?.let(onSeek) }, enabled = nextMs != null) {
            Icon(
                Icons.Filled.FastForward,
                contentDescription = stringResource(R.string.next_chapter),
                tint = transportTint(nextMs != null),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** White when it does something, visibly dimmed at the ends of a queue or a chapter list. */
private fun transportTint(enabled: Boolean): Color =
    if (enabled) Color.White else Color.White.copy(alpha = DISABLED_ALPHA)

/**
 * The playback-speed chip and its menu: the chip shows the current speed, tapping an option
 * applies it immediately. Open state lives here (it is pure interaction), but is reported via
 * [onMenuChanged] so the caller keeps the controls overlay pinned while the menu is up.
 */
@Composable
private fun SpeedMenuButton(
    speed: Float,
    onSetSpeed: (Float) -> Unit,
    onMenuChanged: (Boolean) -> Unit,
) {
    Box {
        var menuOpen by remember { mutableStateOf(false) }
        fun setMenu(open: Boolean) {
            menuOpen = open
            onMenuChanged(open)
        }
        // The chip's text is the bare value ("1×"); the description says what the button *is*.
        val speedLabel = stringResource(R.string.playback_speed)
        TextButton(
            onClick = { setMenu(true) },
            modifier = Modifier.semantics { contentDescription = speedLabel },
        ) {
            Text(formatSpeed(speed), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenu(false) }) {
            PlaybackSpeed.options.forEach { option ->
                SpeedMenuItem(
                    speed = option,
                    selected = option == speed,
                    onClick = {
                        setMenu(false)
                        onSetSpeed(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeedMenuItem(speed: Float, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(formatSpeed(speed)) },
        onClick = onClick,
        // Every item carries the slot so the labels align; only the active speed shows the check.
        leadingIcon = {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
    )
}

/** "1×", "1.5×": trailing zeros dropped and the decimal separator localized. */
@Composable
internal fun formatSpeed(speed: Float): String {
    val numberFormat = remember { NumberFormat.getNumberInstance() }
    return stringResource(R.string.playback_speed_value, numberFormat.format(speed.toDouble()))
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

/** Position label: 0 is a real time here, unlike [formatDuration]'s "unknown" placeholder. */
internal fun formatPosition(ms: Long): String =
    if (ms <= 0) "0:00" else formatDuration(ms / MILLIS_PER_SECOND)

/** What press-and-hold temporarily forces the speed to, until the finger lifts. */
private const val HOLD_SPEED = 3f

private const val POSITION_POLL_MS = 500L
private const val CONTROLS_HIDE_DELAY_MS = 3_000L
private const val INDICATOR_LINGER_MS = 800L
private const val MILLIS_PER_SECOND = 1_000L
private const val SCRIM_ALPHA = 0.4f
private const val DISABLED_ALPHA = 0.35f
private const val PANEL_SCRIM_ALPHA = 0.6f
private const val PANEL_BACKGROUND_ALPHA = 0.92f
private val PANEL_MAX_HEIGHT = 360.dp
private val CHAPTER_TICK_HEIGHT = 8.dp
private val CHAPTER_TICK_WIDTH = 2.dp
