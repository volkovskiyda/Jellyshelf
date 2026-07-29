package com.gmail.volkovskiyda.jellyshelf.ui.theme

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlin.math.hypot

/**
 * How long an armed origin stays valid. A tap that ends up changing nothing visually (light → auto
 * under a light system) must not leave a live origin behind for an unrelated later change — a
 * system dark-mode flip minutes afterwards — to animate from.
 */
private const val ARM_WINDOW_MS = 1_000L

/** Long enough to read as a sweep rather than a flash, with Material's standard easing. */
private const val REVEAL_DURATION_MS = 400

/** Identifies the snapshot overlay to instrumented tests; it has no other semantics. */
internal const val THEME_REVEAL_OVERLAY_TAG = "themeRevealOverlay"

/**
 * Remembers where on screen the next theme change was asked for, so [ThemeReveal] can grow the new
 * theme out of that point instead of swapping colours instantly.
 *
 * Only a user gesture knows both that it caused a change and where it happened, so arming is the
 * caller's job — see [ThemeModeSwitch][com.gmail.volkovskiyda.jellyshelf.ui.settings.ThemeModeSwitch].
 * Everything else — a system dark-mode flip, the cold-start cache correction, restored state —
 * arrives with nothing armed and is applied instantly, which is Now in Android's behaviour: with
 * no origin there is nothing to grow the new theme out of.
 */
@Stable
class ThemeRevealController {
    private var armed: Pair<Offset, Long>? = null

    /** Arms the next effective theme change to reveal from [originInWindow] (window coordinates). */
    fun armReveal(originInWindow: Offset) {
        armed = originInWindow to SystemClock.uptimeMillis()
    }

    /**
     * The armed origin if one is still fresh, else null — and either way the arming is spent, so a
     * tap that changed nothing visually cannot lend its origin to whatever changes the theme next.
     */
    internal fun consumeOrigin(): Offset? {
        val (origin, armedAt) = armed ?: return null
        armed = null
        return origin.takeIf { SystemClock.uptimeMillis() - armedAt <= ARM_WINDOW_MS }
    }

    /** The frame captured before the theme flipped; non-null exactly while the reveal is playing. */
    internal var overlayBitmap by mutableStateOf<ImageBitmap?>(null)
    internal var origin = Offset.Zero
    internal var expandToDark = true
    internal val radius = Animatable(0f)

    val isAnimating: Boolean get() = overlayBitmap != null
}

/**
 * The controller the theme switch arms. Null by default so previews, screenshot tests and component
 * tests compose the switch without providing anything.
 */
val LocalThemeRevealController = staticCompositionLocalOf<ThemeRevealController?> { null }

/**
 * Plays a circular reveal between the last drawn frame and the new theme: going dark the new theme
 * grows out of the armed origin, going light the old dark frame collapses back into it. Either way
 * the circle's interior is the dark side, which is what keeps its edge crisp.
 *
 * [darkTheme] is the *target*, and in this app it arrives asynchronously — a tap travels through the
 * ViewModel and DataStore before it comes back as a new theme. Content therefore themes from
 * [appliedDark][content], which lags the target until the reveal is ready to start, because the old
 * pixels have to be captured *before* anything recomposes in the new theme. (That costs one effect
 * dispatch of lag on every change, animated or not; invisible in practice, worth knowing in a trace.)
 */
@Composable
fun ThemeReveal(
    controller: ThemeRevealController,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (appliedDark: Boolean) -> Unit,
) {
    val layer = rememberGraphicsLayer()
    var applied by remember { mutableStateOf(darkTheme) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Keyed on the target, so a second change mid-reveal simply restarts this effect: the running
    // animateTo is cancelled, `finally` drops the overlay, and the new target is re-evaluated from
    // scratch. That is the whole re-entrancy story — the scrim below keeps taps from re-arming
    // while a reveal plays, and an origin is consumed the moment it is used.
    LaunchedEffect(darkTheme) {
        if (applied == darkTheme) return@LaunchedEffect
        val start = captureRevealStart(controller, layer, coords)
        if (start == null) {
            applied = darkTheme
            return@LaunchedEffect
        }
        controller.origin = start.origin
        controller.expandToDark = darkTheme
        controller.radius.snapTo(if (darkTheme) 0f else start.targetRadius)
        controller.overlayBitmap = start.captured // The overlay covers frame one completely...
        applied = darkTheme // ...so the theme flip underneath it lands unseen.
        // Let that frame pass before starting the clock. Re-theming the whole app is by far the
        // most expensive frame of the change — long enough on a mid-range device that an animation
        // started now would have run a third of its course before anything could be drawn, and the
        // circle would open already half-grown instead of out of the switch.
        withFrameNanos { }
        try {
            controller.radius.animateTo(
                targetValue = if (darkTheme) start.targetRadius else 0f,
                animationSpec = tween(REVEAL_DURATION_MS, easing = FastOutSlowInEasing),
            )
        } finally {
            // Releases the snapshot — an ImageBitmap has no dispose, dropping it is the cleanup —
            // and does so on cancellation too, so a restart never leaves a stale frame on screen.
            controller.overlayBitmap = null
        }
    }

    Box(modifier.onGloballyPositioned { coords = it }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    // Mirror the content into the layer and draw it straight back: the screen looks
                    // untouched, and the layer always holds the frame that is currently on it.
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            content(applied)
        }

        // A sibling of the recorded content rather than a child of it, deliberately: the per-frame
        // radius change then invalidates only the overlay's Canvas, and the snapshot can never end
        // up being recorded back into the very layer it was captured from.
        RevealOverlay(controller)
    }
}

/** Everything a reveal needs the moment it starts: the frame to keep showing, where to grow from, and how far. */
private class RevealStart(val captured: ImageBitmap, val origin: Offset, val targetRadius: Float)

/**
 * Decides whether this theme change can animate, and captures everything the reveal needs if so:
 * the frame currently on screen, the armed origin in local coordinates, and the radius that covers
 * the farthest corner. Null means switch plainly — nothing armed, not yet placed, or nothing drawn
 * to capture.
 */
private suspend fun captureRevealStart(
    controller: ThemeRevealController,
    layer: GraphicsLayer,
    placement: LayoutCoordinates?,
): RevealStart? {
    // Consumed first, unconditionally — a tap's arming is spent even when the reveal can't run.
    val originInWindow = controller.consumeOrigin()
    // The size guard is not optional: toImageBitmap() throws on a zero-sized layer.
    val placed = placement?.takeIf { layer.size.width > 0 && layer.size.height > 0 }
    if (originInWindow == null || placed == null) return null
    val captured = runCatchingCancellable { layer.toImageBitmap() }.getOrNull()
    return captured?.let { bitmap ->
        val local = placed.windowToLocal(originInWindow)
        val width = placed.size.width.toFloat()
        val height = placed.size.height.toFloat()
        // Reach for the farthest corner, so the circle is guaranteed to cover the whole screen.
        val targetRadius = hypot(
            maxOf(local.x, width - local.x),
            maxOf(local.y, height - local.y),
        )
        RevealStart(bitmap, local, targetRadius)
    }
}

/**
 * The captured previous frame, clipped by the animated circle; composes to nothing once the
 * reveal ends and the snapshot is dropped.
 */
@Composable
private fun RevealOverlay(controller: ThemeRevealController) {
    val previousFrame = controller.overlayBitmap ?: return
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag(THEME_REVEAL_OVERLAY_TAG)
            // The reveal owns the screen for its 400 ms; taps would otherwise land on a new
            // UI the user cannot see yet, under a frame that is already stale.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val circle = Path().apply {
            addOval(Rect(controller.origin, controller.radius.value))
        }
        // Going dark: the old light frame everywhere except a growing hole, through which
        // the new dark theme shows. Going light: the old dark frame only inside a shrinking
        // circle, collapsing into the origin over a UI that is already light.
        val clipOp = if (controller.expandToDark) ClipOp.Difference else ClipOp.Intersect
        clipPath(circle, clipOp) { drawImage(previousFrame) }
    }
}
