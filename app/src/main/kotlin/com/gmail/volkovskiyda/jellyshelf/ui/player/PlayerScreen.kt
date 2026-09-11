// All of media3-ui-compose (PlayerSurface, rememberPresentationState, the button-state holders)
// and of media3-ui-compose-material3 (PlayerDefaults, ProgressSlider, the time labels) is
// @UnstableApi, so the whole file opts in rather than annotating every function. ExperimentalApi is
// a second opt-in ProgressSlider needs: only the overload carrying onValueChange — the one that
// lets the scrub drive our chapter-step row — is marked, and it is @RequiresOptIn at ERROR level.
@file:androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)

package com.gmail.volkovskiyda.jellyshelf.ui.player

import android.content.res.Configuration
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
import androidx.media3.ui.compose.state.rememberPlaylistState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberSeekBackButtonState
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState
import coil3.compose.AsyncImage
import com.gmail.volkovskiyda.jellyshelf.LocalIsInPip
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import com.gmail.volkovskiyda.jellyshelf.domain.model.VideoScaleMode
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.PlayerOrigin
import com.gmail.volkovskiyda.jellyshelf.playback.isDecodeFailure
import com.gmail.volkovskiyda.jellyshelf.ui.BackButton
import com.gmail.volkovskiyda.jellyshelf.ui.rememberCopyToClipboard
import com.gmail.volkovskiyda.jellyshelf.ui.rememberVideoThumbnailResolver
import com.gmail.volkovskiyda.jellyshelf.util.currentChapter
import com.gmail.volkovskiyda.jellyshelf.util.formatDuration
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.NumberFormat
import java.util.Formatter
import java.util.Locale
import kotlin.math.roundToInt

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
 * [MediaController].
 *
 * Two deliberate exits, and the difference between them is the whole point:
 *
 *  - **Back** — the back arrow or the system gesture — stops playback, reports the position to the
 *    server and drops the media notification. Back means *done watching*, and that is unchanged.
 *  - **Minimize** — the down-chevron in the top bar — pops this screen and leaves playback running.
 *    The mini-player bar then appears on whatever screen is underneath, and is the way back here.
 *
 * Merely hiding the app also keeps playing. Picture-in-Picture is a *third* exit, and only ever
 * on request — the PiP button in the top bar, never automatically on leaving the app (auto-enter
 * on the Home gesture surprised more than it helped). Expanding that window comes back here with
 * the back stack untouched; closing it destroys the activity while the service plays on, and then
 * the notification (or the mini-player bar on the next launch) is the way back. The media
 * notification is a way back in every case.
 *
 * Orientation is free (sensor); the surface just re-fits.
 *
 * Minimize exists because the bar needs playback to survive leaving the player. It is a *second*
 * exit rather than a change to the first: back stopping playback was decided deliberately, and
 * this does not reopen it.
 *
 * [leaving] is the navigation layer saying this has stopped being the screen. It is not the same
 * moment as the screen going away: a pop lands, the destination composes — which for the library
 * is a couple of hundred milliseconds — and only then does `NavDisplay` swap the two over. For
 * that whole gap the player is still the thing on the display, now over a destination the user
 * has already asked for, and by then it is showing the cover, because the exit stopped playback
 * and emptied the surface underneath it. So it shows nothing instead.
 */
@Composable
fun PlayerScreen(
    youtubeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    origin: PlayerOrigin = PlayerOrigin.None,
    leaving: Boolean = false,
) {
    val viewModel: PlayerViewModel = koinViewModel { parametersOf(AppNavKey.Player(youtubeId, origin)) }
    val controller by viewModel.controller.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val scaleMode by viewModel.videoScaleMode.collectAsStateWithLifecycle()
    val isInPip = LocalIsInPip.current
    val activity = LocalActivity.current
    // The cover to stand in for the video until it has a frame of its own — see [PlayerPoster].
    // Keyed on the video the screen is *for*, so it is on screen before the controller has even
    // connected, and it follows the queue when that advances (the flow tracks the current id).
    val thumbnail = rememberVideoThumbnailResolver()
    val poster = video?.let(thumbnail)

    ImmersiveWhileHere(enabled = !isInPip)

    // The rotate button's landscape request never outlives the player, and never fights PiP. Both
    // ends matter: leaving hands orientation back to the rest of the app, and a request the sensor
    // never got to release (auto-rotate off) would otherwise be permanent.
    DisposableEffect(activity, isInPip) {
        val landscape = (activity as? MainActivity)?.landscape
        if (isInPip) landscape?.release()
        onDispose { landscape?.release() }
    }

    // Every *stopping* exit routes through here — and only those, which is why it is not an
    // onCleared()/lifecycle hook: those also fire on rotation and on minimizing.
    val leave = {
        viewModel.stopPlayback()
        onBack()
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        val c = controller
        // Nothing at all once this is no longer the screen — see [leaving]. Its own black is
        // what is left, and that is the point: a video surface is a hardware layer that a
        // transition's alpha never reaches, so anything still on it would sit fully opaque over
        // the screen taking over.
        if (leaving) {
            Unit
        } else if (c == null) {
            // Still connecting to the service. The back arrow stays reachable regardless — with
            // no controller yet there is nothing to stop, so this is a plain leave.
            PlayerPoster(poster, Modifier.matchParentSize())
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
                // The file name, not the YouTube title: it names the actual file being played,
                // and the overlay title's tap copies it (see PlayerControls).
                title = video?.fileName,
                poster = poster,
                chapters = chapters,
                onSpeedPicked = viewModel::savePlaybackSpeed,
                scaleMode = scaleMode,
                onScaleModePicked = viewModel::setVideoScaleMode,
                isInPip = isInPip,
                onBack = leave,
                // The nav layer's plain pop: no stopPlayback, so the session survives and the
                // mini-player bar picks it up on the screen underneath.
                onMinimize = onBack,
                onEnterPip = { (activity as? MainActivity)?.enterPip() },
                onRotateToLandscape = { (activity as? MainActivity)?.landscape?.request() },
                onSurfaceBounds = { (activity as? MainActivity)?.updatePipParams(rect = it) },
            )
        }
    }
}

/** The connected player: surface, playback-state plumbing, and the tap-to-toggle overlay. */
@Composable
private fun PlayerWithControls(
    controller: MediaController,
    title: String?,
    poster: String?,
    chapters: List<Chapter>,
    onSpeedPicked: (Float) -> Unit,
    scaleMode: VideoScaleMode,
    onScaleModePicked: (VideoScaleMode) -> Unit,
    isInPip: Boolean,
    onBack: () -> Unit,
    onMinimize: () -> Unit,
    onEnterPip: () -> Unit,
    onRotateToLandscape: () -> Unit,
    onSurfaceBounds: (Rect) -> Unit,
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

    // The activity keeps `orientation` in its configChanges, so a rotation recomposes rather than
    // recreating: this is read from composition every time, never cached.
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    // Android 16+ ignores an app's orientation request on a display this wide, so asking there
    // would leave a button that visibly does nothing. Those windows cycle scale modes instead —
    // one flag for both halves, so the icon and the action can never disagree.
    val rotateFirst = isPortrait && configuration.smallestScreenWidthDp < LARGE_SCREEN_WIDTH_DP

    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped by every top-bar tap that keeps the user here, to re-arm the auto-hide below.
    var controlsTouched by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    // A PiP window is a thumbnail of the video and nothing else. Anything overlaid on it is
    // unreadable at that size and unusable through the platform's own tap handling, so the panels
    // are closed on the way in rather than merely hidden — reopening the window should not
    // restore a chapter list the user has long since forgotten leaving open.
    LaunchedEffect(isInPip) {
        if (isInPip) {
            controlsVisible = false
            chaptersOpen = false
            speedMenuOpen = false
        }
    }
    // Auto-hide while playing; scrubbing, the open chapter panel or the speed menu pins the
    // controls (hiding them would tear the open menu out of the composition mid-use).
    val controlsPinned = scrubbing || chaptersOpen || speedMenuOpen
    // controlsTouched and isPortrait are keys so a tap — and the rotation one of them asks for —
    // restart the 3 s rather than letting it run out mid-sequence: reaching Stretch from portrait
    // takes four taps, and the controls hiding between them would strand the user.
    LaunchedEffect(controlsVisible, isPlaying, controlsPinned, controlsTouched, isPortrait) {
        if (controlsVisible && isPlaying && !controlsPinned) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    // One handler for both meanings of Back, so their priority is in the code rather than in the
    // dispatcher's registration order: an open panel consumes the press (closing it must not pop
    // the screen), and otherwise Back is the explicit leave that stops playback.
    BackHandler { if (chaptersOpen) chaptersOpen = false else onBack() }

    // Only the double-tap gesture reads these here; the transport buttons hold their own,
    // remembered against the same controller inside their slot composables.
    val seekBack = rememberSeekBackButtonState(controller)
    val seekForward = rememberSeekForwardButtonState(controller)
    val playbackSpeed = rememberPlaybackSpeedState(controller)
    val presentationState = rememberPresentationState(controller)

    // Where in the queue we are. Off the timeline rather than hand-tracked: the queue arrives
    // asynchronously (the session resolves every id before the timeline exists), and PlaylistState
    // already listens for both the timeline landing and each transition. currentMediaItemIndex is
    // C.INDEX_UNSET until then, so both ends read false — as an empty-timeline read did before.
    //
    // hasPreviousMediaItem()/hasNextMediaItem() respect shuffle order and repeat mode; an index
    // comparison does not. Neither is exposed anywhere — no shuffle or repeat button, and
    // PlaybackService sets neither mode — so the two are equivalent here. Adding a repeat mode is
    // what would make this derivation wrong.
    val playlist = rememberPlaylistState(controller)
    val hasPrevious = playlist.currentMediaItemIndex > 0
    val hasNext = playlist.currentMediaItemIndex in 0..<playlist.mediaItemCount - 1
    // A chapter list belongs to the video it was opened for. The queue can move on while it is up
    // — the transport arrows sit beside it, and the previous video can simply end — so every
    // transition closes it, rather than leaving the next video's list (or, without chapters, a bare
    // heading over a scrim) where the last one's was.
    LaunchedEffect(playlist.currentMediaItemIndex) { chaptersOpen = false }

    // Press-and-hold forces a temporary speed until the finger lifts — 2× to begin with, and
    // whatever the swipe walks it to after that (see HoldSpeedTracker). PlaybackSpeedState
    // remembers the speed to go back to, so a hold started at 1.5× returns to 1.5× however far the
    // swipe wandered. Its release half runs in the pointer handler below, which only exists while
    // this composition does — so a hold interrupted by the composable going away (rotating with
    // the finger still down recreates the activity) would leave the *session* sped up with nothing
    // holding it there. The player outlives this screen, so undoing the hold has to be tied to the
    // screen's lifetime as well as to the finger's.
    DisposableEffect(playbackSpeed) {
        onDispose { playbackSpeed.restoreOverriddenSpeed() }
    }

    // The surface's one drag gesture: horizontal scrubbing (see PlayerGestureHandler). The pill
    // lingers briefly after the finger lifts, then hides.
    var gestureIndicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var gestureActive by remember { mutableStateOf(false) }
    // Press-and-hold's speed, and the swipe that retunes it without lifting. Remembered on the
    // controller like the drag handler, so neither restarts mid-gesture.
    val haptics = LocalHapticFeedback.current
    val holdSpeed = remember(controller) {
        HoldSpeedTracker(object : HoldSpeedTracker.Host {
            override fun onHoldSpeed(speed: Float) {
                playbackSpeed.temporarilyOverrideSpeedWith(speed)
                gestureActive = true
                gestureIndicator = GestureIndicator.Speed(speed)
                // Only the round landmarks tick, the press's own 2× among them: a rung every
                // 32 dp means ticking all eleven would buzz through a slow swipe.
                if (speed in PlaybackSpeed.hapticLandmarks) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }

            override fun onHoldEnd() = Unit
        })
    }
    val gestureHandler = remember(controller) {
        PlayerGestureHandler(object : PlayerGestureHandler.Host {
            // A hold that has begun owns the finger: without this the drag node — the inner of the
            // two pointer nodes, so it reads the Main pass before the tap detector consumes it —
            // would lock a scrub under the swipe and fight it for the pill. canSeek() is read once
            // per gesture, when the drag locks, so refusing here keeps the whole of that gesture
            // off the seek path.
            override fun canSeek() =
                !holdSpeed.isHolding && controller.isCurrentMediaItemSeekable &&
                    controller.duration > 0

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

    // A PiP window's surface can be left behind at an intermediate size (2026-09-02, Pixel 7 Pro
    // on Android 17: the whole frame at ~70 % of the window, top-left, until a pinch resized it).
    // The shell settles the window in steps — the emulator's own log shows an entry size and then
    // a bounds change right after — and the surface's geometry is what a real bounds change
    // refreshes, which is exactly what the pinch supplied. So every size the PiP window takes is
    // followed, two frames later, by a one-pixel inset held for two frames: two genuine bounds
    // changes for the SurfaceView, the second landing it on its final size. Invisible at that
    // scale. Frames rather than a delay because the surface geometry is applied per frame, and
    // two of them so the inset is laid out and drawn, not merely composed and undone.
    val windowSize = LocalWindowInfo.current.containerSize
    var surfaceNudge by remember { mutableStateOf(0.dp) }
    LaunchedEffect(isInPip, windowSize) {
        if (!isInPip) return@LaunchedEffect
        repeat(SURFACE_NUDGE_FRAMES) { withFrameNanos {} }
        surfaceNudge = SURFACE_NUDGE
        repeat(SURFACE_NUDGE_FRAMES) { withFrameNanos {} }
        surfaceNudge = 0.dp
    }

    Box(
        Modifier
            .fillMaxSize()
            // Both gesture nodes are keyed on the controller the button states are themselves
            // remembered from, so neither can restart mid-gesture — and on isInPip, so entering
            // PiP tears them down: a double-tap seek or a press-and-hold from a 200 dp window
            // would be an accident every time, and the platform reads taps there as "expand me".
            .then(
                if (isInPip) {
                    Modifier
                } else {
                    Modifier.playerTapGestures(
                        key = controller,
                        holdSpeed = holdSpeed,
                        onTap = { controlsVisible = !controlsVisible },
                        // Which half was tapped picks the direction; the increments come from the
                        // player, so a double-tap and the buttons cannot disagree.
                        onDoubleTap = { offset, size ->
                            if (offset.x < size.width / 2) {
                                seekBack.onClick()
                            } else {
                                seekForward.onClick()
                            }
                        },
                        onHoldRelease = {
                            playbackSpeed.restoreOverriddenSpeed()
                            gestureActive = false
                        },
                    )
                },
            )
            .then(if (isInPip) Modifier else Modifier.playerDragGestures(gestureHandler)),
    ) {
        // The PiP source rect is the *fitted* video rect whatever the screen is showing, so it is
        // reported from its own node rather than from the surface: in Zoom the surface is measured
        // wider than the window and its bounds are no longer the video's place in it. Laid out by
        // the same media3 modifier the surface uses in Fit, so the rect is unchanged from before
        // the modes existed — that is what the PiP transition animates from and back into (see
        // MainActivity.updatePipParams). The nudge is deliberately absent: it only applies inside
        // PiP, where every report is discarded anyway.
        Spacer(
            Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentationState.videoSizeDp)
                .onGloballyPositioned { onSurfaceBounds(it.boundsInWindow().toAndroidRect()) },
        )
        PlayerSurface(
            player = controller,
            modifier = Modifier
                .align(Alignment.Center)
                // Outside the resize, so the inset shrinks the box the video is scaled into
                // and the SurfaceView's bounds change with it — which is the point: a genuine
                // bounds change is what refreshes the surface's geometry.
                .padding(surfaceNudge)
                // Zoom and Stretch are the landscape player's alone: a PiP window is a thumbnail
                // of the whole frame, and cropping a 16:9 video into a portrait window would throw
                // most of the picture away. The mode is remembered through both.
                .resizeWithContentScale(
                    if (isInPip || rotateFirst) ContentScale.Fit else scaleMode.contentScale,
                    presentationState.videoSizeDp,
                ),
        )
        if (presentationState.coverSurface) {
            // Shutter until the first frame renders: solid black beats a stale/blank surface.
            Box(Modifier.matchParentSize().background(Color.Black))
            PlayerPoster(poster, Modifier.matchParentSize())
        }
        if (isBuffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        // PiP stays a hard `if` — a PiP window must not compose controls at all — while
        // controlsVisible moves inside, because PlayerDefaults' layouts animate on it and gating
        // the whole call would skip the fade.
        if (!isInPip) {
            PlayerControls(
                player = controller,
                visible = controlsVisible,
                title = title,
                positionMs = positionMs,
                durationMs = durationMs,
                chapters = chapters,
                speed = playbackSpeed.playbackSpeed,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                // The explicit *MediaItem* variants: seekToPrevious() would restart the current
                // video once past its threshold, which is a different button entirely.
                onPrevious = controller::seekToPreviousMediaItem,
                onNext = controller::seekToNextMediaItem,
                // Only the chapter row and the chapters panel seek through us now; the seek bar's
                // own seek is ProgressSlider's, fired just before its onValueChangeFinished.
                onSeek = controller::seekTo,
                onSetSpeed = { speed ->
                    playbackSpeed.updatePlaybackSpeed(speed)
                    // Only a menu pick is a choice worth keeping. Press-and-hold's speed is a
                    // temporary override on the same state, and is never saved.
                    onSpeedPicked(speed)
                },
                rotateFirst = rotateFirst,
                scaleMode = scaleMode,
                onCycleScaleMode = {
                    val next = scaleMode.next()
                    onScaleModePicked(next)
                    // gestureActive stays false, so the existing linger effect clears it.
                    gestureIndicator = GestureIndicator.ScaleMode(next)
                    controlsTouched++
                },
                // No pill: the rotation is its own feedback, and would be mid-flight anyway.
                onRotateToLandscape = {
                    onRotateToLandscape()
                    controlsTouched++
                },
                onScrubbingChanged = { scrubbing = it },
                onSpeedMenuChanged = { speedMenuOpen = it },
                onOpenChapters = { chaptersOpen = true },
                onBack = onBack,
                onMinimize = onMinimize,
                onEnterPip = onEnterPip,
            )
        }
        if (chaptersOpen && !isInPip) {
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
        gestureIndicator?.takeIf { !isInPip }?.let { indicator ->
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
 * The video's own cover, shown wherever the surface has nothing to show yet.
 *
 * Opening a video is not instant — a controller connects, the session resolves the id, the server
 * is asked for the stream, and only then does a frame decode — and for all of it the surface is
 * covered. Black is the honest answer, but the *thumbnail* is a better one: it is the same image
 * the row the user just tapped was showing, already in Coil's cache, so the tap reads as that
 * thumbnail growing to fill the screen rather than as a wait. There is nothing to generate for
 * this and no frame to extract — every video carries the cover yt-dlp fetched with it.
 *
 * Drawn over the shutter rather than instead of it, and letterboxed like the video it stands in
 * for, so a cover of a different shape does not crop or stretch to fill. Null (a video with no
 * thumbnail, or settings not yet loaded) simply leaves the black in place.
 *
 * It also outlasts the wait for an audio-only stream, where no video track is ever selected and
 * the surface stays covered for the whole playback — a cover there beats a black rectangle.
 */
@Composable
private fun PlayerPoster(model: String?, modifier: Modifier = Modifier) {
    if (model == null) return
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/**
 * The controls overlay: top bar (back + minimize + title + speed menu + chapters), centre transport
 * row, bottom chapter-step row and position–seek–duration bar with chapter tick markers.
 *
 * The three rows are media3's [PlayerDefaults] layouts filled with our own buttons — we take the
 * layouts, the fade and the bottom gradient, not the default slot contents, whose icons and
 * generic content descriptions would replace ours and lose the asymmetric 10 s/30 s seek labels.
 * Everything transport reads its state off [player] rather than off parameters, which is why
 * previews and tests hand it a `FakePlayer` instead of plain values; [positionMs] and [durationMs]
 * stay as parameters because the chapter-step row and the tick markers need a *scrub-aware*
 * position the player itself does not have.
 *
 * Its only internal state is transient interaction — the in-flight scrub and the open speed menu —
 * each reported via its `on*Changed` callback so the caller can pin the overlay open while the user
 * is mid-gesture.
 */
@Composable
@Suppress("LongParameterList") // A stateless overlay: every control it renders is one more pair.
internal fun PlayerControls(
    player: Player?,
    visible: Boolean,
    title: String?,
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    speed: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    rotateFirst: Boolean,
    scaleMode: VideoScaleMode,
    onCycleScaleMode: () -> Unit,
    onRotateToLandscape: () -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onSpeedMenuChanged: (Boolean) -> Unit,
    onOpenChapters: () -> Unit,
    onBack: () -> Unit,
    onMinimize: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // While dragging, the chapter row shows the scrub target. Unlike before, the seek itself is
    // ProgressSlider's — it calls seekTo internally just before onValueChangeFinished — so scrubMs
    // exists only to feed ChapterStepRow and to report scrubbing upward.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = scrubMs ?: positionMs

    // No full-screen scrim: BottomControls paints its own gradient behind the seek bar, so more of
    // the video stays visible while the controls are up. The centre buttons and the top bar then
    // sit on raw video and carry their own backings instead. The controls stay clear of the display
    // cutout, whose insets — unlike the hidden system bars' — never drop to zero on notched devices.
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        // Each PlayerDefaults layout goes inside its own aligned Box rather than being handed a
        // Modifier.align: they apply the modifier they are given to the content *inside* their
        // AnimatedVisibility, so the parent data never reaches this Box and all three would stack
        // in the top-start corner.
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            PlayerDefaults.TopControls(player = player, visible = visible) {
                // The bar itself is unchanged; it only moves into the slot, so it fades with the
                // rest rather than popping. Its own gradient stands in for the scrim that used to
                // back it — without one the white title and icons wash out over a bright frame.
                Row(
                    Modifier.fillMaxWidth().background(topControlsGradient()).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onClick = onBack, tint = Color.White)
                    IconButton(onClick = onMinimize) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_minimize),
                            tint = Color.White,
                        )
                    }
                    // Tapping the title copies it (it carries the file name). The tap lands on
                    // the bar, not the surface, so it can't double as a controls-hide toggle.
                    val copyFileName = rememberCopyToClipboard(R.string.file_name_copied)
                    Text(
                        title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                enabled = title != null,
                                onClickLabel = stringResource(R.string.copy_file_name),
                            ) { title?.let(copyFileName) },
                    )
                    SpeedMenuButton(
                        speed = speed,
                        onSetSpeed = onSetSpeed,
                        onMenuChanged = onSpeedMenuChanged,
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
                    // Always enabled, even paused: unlike the auto-enter this replaced, a tap is
                    // an explicit ask, so a still-frame window can't be a surprise.
                    IconButton(onClick = onEnterPip) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt,
                            contentDescription = stringResource(R.string.player_pip),
                            tint = Color.White,
                        )
                    }
                    // One slot, two jobs — see [rotateFirst]. They are alternatives rather than
                    // additions, so the bar gains no width in the orientation that has least.
                    if (rotateFirst) {
                        IconButton(onClick = onRotateToLandscape) {
                            Icon(
                                Icons.Filled.ScreenRotation,
                                contentDescription = stringResource(R.string.player_rotate_landscape),
                                tint = Color.White,
                            )
                        }
                    } else {
                        val modeLabel = stringResource(scaleMode.labelRes())
                        IconButton(
                            onClick = onCycleScaleMode,
                            // The mode is the button's *state*, so TalkBack reads "Video scale
                            // mode, Zoom" while the label a test clicks on stays put.
                            modifier = Modifier.semantics { stateDescription = modeLabel },
                        ) {
                            Icon(
                                Icons.Filled.AspectRatio,
                                contentDescription = stringResource(R.string.player_scale_mode),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // media3's layout, our buttons. Five buttons at the default CenterControlsSpacing overflow
        // a portrait phone (the play button alone is 72.dp and the rest carry 48.dp touch targets),
        // so the gaps shrink rather than the targets — hence the explicit 20.dp.
        Box(Modifier.align(Alignment.Center)) {
            PlayerDefaults.CenterControls(
                player = player,
                visible = visible,
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                backSecondary = {
                    TransportButton(
                        onClick = onPrevious,
                        enabled = hasPrevious,
                        icon = Icons.Filled.SkipPrevious,
                        descriptionRes = R.string.previous_video,
                    )
                },
                back = { SeekBackControl(it) },
                central = { PlayPauseControl(it) },
                forward = { SeekForwardControl(it) },
                forwardSecondary = {
                    TransportButton(
                        onClick = onNext,
                        enabled = hasNext,
                        icon = Icons.Filled.SkipNext,
                        descriptionRes = R.string.next_video,
                    )
                },
            )
        }

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            PlayerDefaults.BottomControls(
                player = player,
                visible = visible,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                above = { if (chapters.isNotEmpty()) ChapterStepRow(chapters, shownMs, onSeek) },
                left = {
                    // The tag the baseline profile's playback leg waits on. PositionText formats
                    // through Util.getStringForTime — "%02d:%02d" below an hour, so zero reads
                    // "00:00", not the "0:00" our own formatPosition produces. That is what
                    // BaselineProfileGenerator.ZERO_POSITION has to match.
                    //
                    // While the thumb is down the label previews where it will land instead:
                    // PositionText reads only the player's live position and ProgressSlider seeks
                    // on release, so on a chapterless video it is the one numeric feedback of
                    // where the finger is headed. Same format, colour and tabular figures as
                    // PositionText renders, so nothing jumps when a drag begins or ends.
                    val previewMs = scrubMs
                    if (previewMs != null) {
                        Text(
                            formatPlayerTime(previewMs),
                            Modifier.testTag(PLAYER_POSITION_TAG).padding(end = 12.dp),
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                    } else {
                        PositionText(
                            it,
                            Modifier.testTag(PLAYER_POSITION_TAG).padding(end = 12.dp),
                            color = Color.White,
                        )
                    }
                },
                right = { DurationText(it, Modifier.padding(start = 12.dp), color = Color.White) },
                progressSlider = {
                    ProgressSlider(
                        player = it,
                        modifier = Modifier.chapterTicks(chapters, durationMs),
                        onValueChange = { fraction ->
                            if (durationMs > 0) {
                                if (scrubMs == null) onScrubbingChanged(true)
                                scrubMs = (fraction * durationMs).toLong()
                            }
                        },
                        onValueChangeFinished = {
                            scrubMs = null
                            onScrubbingChanged(false)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                },
            )
        }
    }
}

/**
 * A transport arrow that dims rather than disappears at its end of the queue.
 *
 * Previous and next keep their own callbacks and their own enabled state rather than reading the
 * player: they call the explicit `seekTo*MediaItem` variants, not the `COMMAND_SEEK_TO_*` ones
 * media3's own buttons bind to, and binding to those would light the previous arrow up on a
 * single-item queue.
 */
@Composable
private fun TransportButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    descriptionRes: Int,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.controlBacking()) {
        Icon(
            icon,
            contentDescription = stringResource(descriptionRes),
            tint = transportTint(enabled),
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * The asymmetric seek buttons. The increments themselves live on the player — media3's own state
 * holders read `seekBackIncrement`/`seekForwardIncrement` — but the labels are the only place the
 * 10 s/30 s split is visible to a screen reader, so they stay ours rather than media3's generic
 * ones.
 */
@Composable
private fun SeekBackControl(player: Player?) {
    val state = rememberSeekBackButtonState(player)
    IconButton(onClick = state::onClick, modifier = Modifier.controlBacking()) {
        Icon(
            Icons.Filled.Replay10,
            contentDescription = stringResource(R.string.seek_back_10),
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun SeekForwardControl(player: Player?) {
    val state = rememberSeekForwardButtonState(player)
    IconButton(onClick = state::onClick, modifier = Modifier.controlBacking()) {
        Icon(
            Icons.Filled.Forward30,
            contentDescription = stringResource(R.string.seek_forward_30),
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** The centre button, larger than the rest, off media3's own play/pause state. */
@Composable
private fun PlayPauseControl(player: Player?) {
    val state = rememberPlayPauseButtonState(player)
    IconButton(onClick = state::onClick, modifier = Modifier.size(72.dp).controlBacking()) {
        Icon(
            if (state.showPlay) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = stringResource(
                if (state.showPlay) R.string.play else R.string.pause,
            ),
            tint = Color.White,
            modifier = Modifier.size(56.dp),
        )
    }
}

/**
 * The circular translucent disc behind each centre button.
 *
 * With the full-screen scrim gone the transport sits on raw video, and white-on-white is
 * unreadable over a bright frame. media3's own `PlayerDefaults.mediumButtonModifier` does exactly
 * this and is `internal`, so this replicates the idea rather than calling it.
 */
private fun Modifier.controlBacking(): Modifier =
    background(Color.Black.copy(alpha = CONTROL_BACKING_ALPHA), CircleShape)

/**
 * The gradient behind the top bar, mirroring the one [PlayerDefaults.BottomControls] paints for
 * itself: dark enough at the very top for white text, gone by the bottom of the row.
 */
@Composable
private fun topControlsGradient(): Brush = Brush.verticalGradient(
    listOf(Color.Black.copy(alpha = TOP_GRADIENT_ALPHA), Color.Transparent),
)

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
 * Long-pressing a row copies its title to the clipboard and leaves the panel open, so the next
 * one can be copied without reopening it.
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
            val copyChapter = rememberCopyToClipboard(R.string.chapter_copied)
            val copyLabel = stringResource(R.string.copy_chapter)
            LazyColumn {
                items(chapters, key = Chapter::startMs) { chapter ->
                    val highlight = chapter == currentChapter
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // A row of label-sized text is 40 dp on its own: short of the 48 dp
                            // touch target the accessibility checks in PlayerControlsTest enforce.
                            .heightIn(min = MIN_TOUCH_TARGET)
                            .combinedClickable(
                                onClick = { onChapterClick(chapter) },
                                onLongClick = { copyChapter(chapter.title) },
                                onLongClickLabel = copyLabel,
                            )
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
 *
 * Off while the app is a Picture-in-Picture window: there are no system bars in that window to
 * hide, and the swipe behaviour it sets belongs to the full-screen activity it will be again.
 */
@Composable
private fun ImmersiveWhileHere(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
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

/**
 * [ms] in the "%02d:%02d" shape media3's [PositionText] shows, through the same
 * [Util.getStringForTime], so the scrub preview neither changes width nor style against the label
 * it temporarily replaces. [formatPosition]'s single-digit minutes belong to the gesture overlay's
 * large indicator, which never sits beside a media3-formatted duration.
 */
private fun formatPlayerTime(ms: Long): String {
    val builder = StringBuilder()
    return Util.getStringForTime(builder, Formatter(builder, Locale.getDefault()), ms)
}

private const val POSITION_POLL_MS = 500L
private const val CONTROLS_HIDE_DELAY_MS = 3_000L
private const val INDICATOR_LINGER_MS = 800L

/** Android 16+ ignores an app's orientation request at or above this window width. */
private const val LARGE_SCREEN_WIDTH_DP = 600

/** See the PiP surface nudge in [PlayerWithControls]. */
private const val SURFACE_NUDGE_FRAMES = 2
private val SURFACE_NUDGE = 1.dp
private const val MILLIS_PER_SECOND = 1_000L

/** The disc behind each centre transport button, now that no full-screen scrim backs them. */
private const val CONTROL_BACKING_ALPHA = 0.35f

/** The top bar's own gradient, standing in for the scrim BottomControls replaced below. */
private const val TOP_GRADIENT_ALPHA = 0.5f
private const val DISABLED_ALPHA = 0.35f
private const val PANEL_SCRIM_ALPHA = 0.6f
private const val PANEL_BACKGROUND_ALPHA = 0.92f
private val PANEL_MAX_HEIGHT = 360.dp
private val MIN_TOUCH_TARGET = 48.dp
private val CHAPTER_TICK_HEIGHT = 8.dp
private val CHAPTER_TICK_WIDTH = 2.dp

/** Integer window pixels, as `PictureInPictureParams` wants them; a whole-pixel rounding, not a truncation. */
private fun androidx.compose.ui.geometry.Rect.toAndroidRect(): Rect =
    Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
