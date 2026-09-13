package com.gmail.volkovskiyda.jellyshelf.ui

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.metrics.performance.JankStats
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.util.Metrics
import com.gmail.volkovskiyda.jellyshelf.util.Span
import com.gmail.volkovskiyda.jellyshelf.util.Spans
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reports one Firebase Performance trace per screen visit, carrying the time to its first frame and
 * how well the frames after it went.
 *
 * **Why the app counts its own frames.** Firebase's screen rendering is measured per `Activity`,
 * and this app is one Activity with six Compose destinations inside it — the console shows
 * `MainActivity` and can never show more. Kotzilla reports Compose destinations properly (see
 * `KotzillaScreen`), but only per session and only where its keys are present. So for the field
 * numbers, the app measures: `JankStats` hands over one callback per frame with the UI-thread
 * duration and its own jank verdict, and each open visit counts the frames drawn during it.
 *
 * **Release only**, like every other Firebase Performance path here (`JellyshelfApplication` turns
 * collection off in debug). There would be nothing to report from a debug build, and a per-frame
 * callback is not something to hand the Compose test suites for free.
 *
 * **A frame counts for every visit open when it arrives.** During a screen transition both entries
 * are composed for about 300 ms and both are on screen, so both count it. That is the honest answer
 * to "which screen was being drawn"; the alternative is to pick one and be wrong half the time.
 *
 * **Cost.** One Firebase event per visit. A device sends at most 300 trace events per 10 minutes
 * (shared with network traces and every other custom trace), and a busy minute of tab-switching is
 * on the order of ten visits — so this is comfortable, but it is also the budget any *further*
 * per-interaction span has to fit into alongside.
 *
 * @param elapsedRealtime the monotonic clock, injected for tests, for the same reason [Metrics]
 *   takes one.
 */
class ScreenFrames(
    private val metrics: Metrics,
    private val buildInfo: BuildInfo,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    /**
     * The visits currently composed, written from the main thread and read from the thread
     * `JankStats` delivers frames on — which on API 24 and up is a `HandlerThread` of its own, not
     * the main thread. A `MutableStateFlow` because that is how this project holds a shared mutable
     * slot, and because `update` gives the list a safe read-modify-write.
     */
    private val open = MutableStateFlow<List<ScreenVisit>>(emptyList())

    /**
     * Whether a frame tracker is already attached.
     *
     * [Track] is one call in one composable, but "composed once" is a weaker promise than it looks:
     * the theme reveal draws the outgoing content alongside the incoming one, and an Activity
     * recreation can overlap two compositions. Two attached trackers would each deliver every
     * frame, and every count below would quietly double — the kind of wrong number that looks like
     * a regression. So the second one attaches nothing.
     */
    private val tracking = MutableStateFlow(false)

    /**
     * Tracks frames for as long as this composition lives.
     *
     * Tied to the composition rather than to `onCreate`/`onDestroy` so the tracker cannot outlive
     * the window it was built from: this Activity is recreated on configuration changes it does not
     * handle itself, and a `JankStats` left attached to a dead window is a leak.
     */
    @Composable
    fun Track() {
        val activity = LocalActivity.current
        if (activity != null && !buildInfo.isDebug) {
            DisposableEffect(activity) {
                val attached = tracking.compareAndSet(expect = false, update = true)
                val stats = attached.takeIf { it }?.let {
                    JankStats.createAndTrack(activity.window) { frame ->
                        onFrame(frame.frameDurationUiNanos, frame.isJank)
                    }
                }
                onDispose {
                    stats?.isTrackingEnabled = false
                    if (attached) tracking.value = false
                }
            }
        }
    }

    /**
     * Opens a visit to [key] for as long as this entry is composed.
     *
     * The span begins in the effect rather than in `remember`, so a composition that is built and
     * then thrown away without ever being applied leaves no span behind to abandon.
     */
    @Composable
    fun Visit(key: AppNavKey) {
        if (buildInfo.isDebug) return
        val visit = remember(key) { newVisit(key) }
        DisposableEffect(visit) {
            begin(visit)
            onDispose { end(visit) }
        }
        // The first frame the platform produces after this entry is composed. Not the same thing as
        // the screen being *finished* — an image still has to load — but it is the moment something
        // other than the previous screen is on the glass, and it is what "first draw" means here.
        LaunchedEffect(visit) {
            withFrameNanos { visit.firstDrawn() }
        }
    }

    /** The visit a [Visit] of [key] would open. Its own function so a test drives the real map. */
    internal fun newVisit(key: AppNavKey): ScreenVisit = ScreenVisit(spanFor(key), elapsedRealtime)

    internal fun begin(visit: ScreenVisit) {
        visit.begin(metrics)
        open.update { it + visit }
    }

    internal fun end(visit: ScreenVisit) {
        open.update { it - visit }
        visit.end()
    }

    /** One frame, attributed to every visit open when it arrived. */
    internal fun onFrame(durationNanos: Long, isJank: Boolean) {
        val frozen = durationNanos >= FROZEN_FRAME_NANOS
        open.value.forEach { it.onFrame(isJank = isJank, frozen = frozen) }
    }

    private fun spanFor(key: AppNavKey): Span = when (key) {
        AppNavKey.Library -> Spans.SCREEN_LIBRARY
        AppNavKey.Categories -> Spans.SCREEN_CATEGORIES
        AppNavKey.Settings -> Spans.SCREEN_SETTINGS
        is AppNavKey.CategoryVideos -> Spans.SCREEN_CATEGORY_VIDEOS
        is AppNavKey.Detail -> Spans.SCREEN_DETAIL
        is AppNavKey.Player -> Spans.SCREEN_PLAYER
    }

    internal companion object {

        /** Kotzilla groups this area's marks under its own track. */
        const val TRACK_SCREENS = "screens"

        /**
         * Firebase's own definition of a frozen frame, reused so the numbers mean the same thing in
         * the console as the ones it computes for `MainActivity` right beside them.
         */
        const val FROZEN_FRAME_NANOS = 700_000_000L
    }
}

/**
 * One visit, counting frames until it ends.
 *
 * The counters are written from the `JankStats` thread and read on the main thread when the visit
 * ends, so they are `MutableStateFlow` slots rather than plain fields.
 */
internal class ScreenVisit(private val span: Span, private val elapsedRealtime: () -> Long) {

    private val frames = MutableStateFlow(0L)
    private val jankFrames = MutableStateFlow(0L)
    private val frozenFrames = MutableStateFlow(0L)
    private val firstDrawMs = MutableStateFlow(-1L)

    private var openSpan: Metrics.OpenSpan? = null
    private var startedAt = 0L

    fun begin(metrics: Metrics) {
        startedAt = elapsedRealtime()
        openSpan = metrics.begin(span, track = ScreenFrames.TRACK_SCREENS)
    }

    fun firstDrawn() {
        firstDrawMs.update { if (it < 0) elapsedRealtime() - startedAt else it }
    }

    fun onFrame(isJank: Boolean, frozen: Boolean) {
        frames.update { it + 1 }
        if (isJank) jankFrames.update { it + 1 }
        if (frozen) frozenFrames.update { it + 1 }
    }

    /**
     * Reports the visit. Four of Firebase's thirty-two metrics; the trace's own Duration is how
     * long the screen was composed, which is what the console shows first.
     *
     * A visit that never drew — composed and disposed inside one frame, as the far side of a
     * cancelled transition is — reports no `first_draw_ms` rather than a zero.
     */
    fun end() {
        val drawn = firstDrawMs.value
        openSpan?.end(
            metrics = buildMap {
                put("frames", frames.value)
                put("jank_frames", jankFrames.value)
                put("frozen_frames", frozenFrames.value)
                if (drawn >= 0) put("first_draw_ms", drawn)
            },
        )
        openSpan = null
    }
}
