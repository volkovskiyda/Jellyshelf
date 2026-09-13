package com.gmail.volkovskiyda.jellyshelf.util

import android.os.SystemClock
import androidx.tracing.trace
import com.google.firebase.perf.FirebasePerformance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.updateAndGet
import androidx.tracing.Trace as SystemTrace

/**
 * One measured span, named once for all three readers of it.
 *
 * [section] is the `androidx.tracing` section name — the dotted `Jellyshelf.` strings catalogued in
 * [Traces], which Perfetto draws and `:baselineprofile`'s `JourneyBenchmark` measures. [id] is the
 * snake_case name both clouds file it under: a Firebase custom trace and the label on Kotzilla's
 * session timeline. Keeping the pair together is the whole point — a number from the benchmark, a
 * number from the Firebase console and a mark on a Kotzilla timeline describe the same work only
 * as long as nobody renames one of them alone.
 *
 * Firebase's rules bound [id]: at most 100 characters and no leading underscore (that prefix is
 * reserved for its own automatic traces). Kotzilla imposes none.
 */
class Span(val section: String, val id: String) {

    /**
     * The section name for the *first* emission of a flow, as [firstContent] measures it.
     *
     * Deliberately not [section]: the per-emission section is summed by `TraceSectionMetric` in
     * `JourneyBenchmark`, and a second, longer slice under the same name would silently inflate
     * that number rather than fail.
     */
    val firstSection: String get() = "$section.first"
}

/** Every span this app measures, by the name all three sinks share. See [Span]. */
object Spans {

    /** A full library sync. The Firebase console has history under this id — never rename it. */
    val LIBRARY_SYNC = Span(Traces.LIBRARY_SYNC, "library_sync")

    /** A tap to the first frame it produces. Firebase history too — never rename it. */
    val PLAYER_STARTUP = Span(Traces.PLAYER_STARTUP, "player_startup")

    /**
     * The library's own list, one collection at a time: how long from subscribing to the first rows
     * being ready, and how many there were. The per-emission section [Traces.LIBRARY_BROWSE] still
     * records every emission beside it.
     */
    val LIBRARY_BROWSE = Span(Traces.LIBRARY_BROWSE, "library_browse")

    /** One ranked search of the library, per debounced query. */
    val LIBRARY_SEARCH = Span(Traces.LIBRARY_SEARCH, "library_search")

    /** One category's videos. Separate from [LIBRARY_BROWSE] — see [Traces.CATEGORY_VIDEOS]. */
    val CATEGORY_VIDEOS = Span(Traces.CATEGORY_VIDEOS, "category_videos")

    /** The Categories tab's list, with its per-row counts. */
    val CATEGORIES_LIST = Span(Traces.CATEGORIES_LIST, "categories_list")

    /** One ranked search of the categories, per debounced query. */
    val CATEGORIES_SEARCH = Span(Traces.CATEGORIES_SEARCH, "categories_search")

    /** The "Others" tab's six counts. */
    val CATEGORIES_OTHERS = Span(Traces.CATEGORIES_OTHERS, "categories_others")

    /**
     * One visit to one screen, from the moment it is composed to the moment it is disposed.
     *
     * Kotzilla reports these itself, per destination, once the navigation entry registers them.
     * Firebase cannot: its screen rendering is measured per `Activity` and this app has one, so
     * these spans are how a Compose destination gets a row in that console at all. The numbers
     * therefore exist in both places on purpose.
     *
     * The section names are not in [Traces] because nothing reads them back — `Traces` is the
     * contract with `JourneyBenchmark`, and a per-visit slice is for a human looking at a
     * recording. `ui.ScreenFrames` maps a navigation key to one of these.
     */
    val SCREEN_LIBRARY = Span("Jellyshelf.screen.Library", "screen_library")
    val SCREEN_CATEGORIES = Span("Jellyshelf.screen.Categories", "screen_categories")
    val SCREEN_CATEGORY_VIDEOS = Span("Jellyshelf.screen.CategoryVideos", "screen_category_videos")
    val SCREEN_DETAIL = Span("Jellyshelf.screen.Detail", "screen_detail")
    val SCREEN_PLAYER = Span("Jellyshelf.screen.Player", "screen_player")
    val SCREEN_SETTINGS = Span("Jellyshelf.screen.Settings", "screen_settings")
}

/**
 * One open Firebase trace, behind an interface so the facade can be unit-tested.
 *
 * `com.google.firebase.perf.metrics.Trace` is final and needs a live `FirebaseApp`, which a JVM
 * test has no way to provide. The limits it enforces are worth knowing at the call site: at most 5
 * attributes and 32 metrics per trace, attribute names `A-Za-z_` and at most 32 characters, values
 * at most 100. Exceeding any of them drops the value with a log line rather than throwing.
 */
interface CloudTrace {
    fun attribute(name: String, value: String)
    fun metric(name: String, value: Long)

    /**
     * Reports the trace. A trace that is never stopped is never reported at all, which is how
     * every abandoned span in this app disappears instead of reporting a wrong duration.
     */
    fun stop()
}

/** Opens [CloudTrace]s. The one seam between [Metrics] and Firebase. */
fun interface CloudTraces {
    fun start(id: String): CloudTrace
}

/**
 * The real thing. Collection is off in debug builds (see `JellyshelfApplication`), where these
 * calls become cheap no-ops rather than needing a gate of their own.
 */
internal class FirebaseCloudTraces : CloudTraces {
    override fun start(id: String): CloudTrace {
        val trace = FirebasePerformance.getInstance().newTrace(id)
        trace.start()
        return object : CloudTrace {
            override fun attribute(name: String, value: String) = trace.putAttribute(name, value)
            override fun metric(name: String, value: Long) = trace.putMetric(name, value)
            override fun stop() = trace.stop()
        }
    }
}

/**
 * What this app can say to Kotzilla, behind an interface because the SDK is not always there.
 *
 * The Kotzilla Gradle plugin adds the SDK runtime only when `app/kotzilla.json` exists; a keyless
 * checkout — a fresh clone, a fork's pull request, CI's build and test jobs — has no `KotzillaCore`
 * class on the classpath at all. So every call into it lives behind this interface, implemented
 * twice under `src/kotzillaEnabled` and `src/kotzillaDisabled`, exactly as `monitoring()` already
 * is. Nothing in `src/main` may name a Kotzilla type.
 *
 * There is deliberately **no** synchronous `trace(id) { }` here, even though the SDK has one. The
 * one block-shaped span this app measures that way is the library sync, whose block contains a
 * non-local `return` — that survives an inline function chain and cannot survive being handed to
 * an interface method as a lambda. [Metrics.span] therefore times the block itself and reports it
 * the same way the asynchronous spans do, with [mark] and [log].
 */
interface MetricsSink {

    /**
     * Wraps a suspending block in a real Kotzilla trace, which is the only shape whose duration
     * the console aggregates. Used where the work genuinely is one suspending block.
     */
    suspend fun <T> suspendTrace(id: String, block: suspend () -> T): T

    /** A point on the session timeline, grouped under [track]. */
    fun mark(label: String, track: String)

    /** A line on the session timeline. Where a measured duration ends up. */
    fun log(message: String)
}

/**
 * Writes every measured span to the system trace, Kotzilla and Firebase Performance under one name.
 *
 * Three readers, three different questions, one set of names (see [Span]):
 *
 *  - **Perfetto and `JourneyBenchmark`** read the system-trace sections. Local, exact, free, on
 *    every build type, and the only one that can fail a regression on a number.
 *  - **Kotzilla** reads marks, logs and traces. Per session rather than aggregated, and — unlike
 *    Firebase — live on debug builds, which is what makes it useful while building.
 *  - **Firebase Performance** reads custom traces. Release only, sampled, aggregated, minutes
 *    late; the one that describes real installs.
 *
 * Two shapes. [span] and [suspendSpan] wrap a block. [begin] opens a span that some *other*
 * callback closes — a tap that ends at a rendered frame, a flow that ends at its first emission —
 * and hands back an [OpenSpan] to close or abandon it.
 *
 * @param elapsedRealtime the monotonic clock, injected for tests. `SystemClock.elapsedRealtime` and
 *   not the app's `TimeProvider`, which is wall time and says in its own KDoc that it can jump
 *   backwards — the same reason `IdleReconnectDataSource` reaches for it directly.
 */
class Metrics(
    private val kotzilla: MetricsSink,
    private val cloud: CloudTraces,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    /**
     * Async trace sections are identified by an int cookie, and two sections with the same name may
     * overlap — two screens during a transition, two flows collected at once. A counter, not a hash
     * of anything, so nothing can collide.
     *
     * A `MutableStateFlow` rather than an atomic: this project's shared mutable slots are all
     * `MutableStateFlow`, and `updateAndGet` is the compare-and-set it needs.
     */
    private val cookies = MutableStateFlow(0)

    /**
     * Measures [block] as one span, reporting it whichever way it exits.
     *
     * `inline` and public all the way down on purpose: the library sync's block returns non-locally
     * out of the enclosing function on an early failure, and that only survives if nothing in
     * between takes the block as a non-inlined lambda.
     */
    inline fun <T> span(span: Span, track: String = TRACK_APP, block: (CloudTrace) -> T): T =
        trace(span.section) {
            val open = begin(span, track = track, section = null)
            try {
                block(open.cloud)
            } finally {
                open.end()
            }
        }

    /**
     * Measures a suspending [block] as one span.
     *
     * The system section is asynchronous because a suspending block can resume on a thread other
     * than the one it began on, and `beginSection`/`endSection` are thread-confined — the same
     * reason `Traces.PLAYER_RESOLVE` is async. Kotzilla gets a real trace here rather than the
     * mark pair [span] leaves, because a suspending block is a shape its API can take whole.
     */
    suspend fun <T> suspendSpan(span: Span, block: suspend (CloudTrace) -> T): T {
        val cookie = nextCookie()
        SystemTrace.beginAsyncSection(span.section, cookie)
        val cloudTrace = cloud.start(span.id)
        return try {
            kotzilla.suspendTrace(span.id) { block(cloudTrace) }
        } finally {
            cloudTrace.stop()
            SystemTrace.endAsyncSection(span.section, cookie)
        }
    }

    /**
     * Opens a span that something else closes. See [OpenSpan].
     *
     * @param section the system-trace section to open, or null for none — [span] passes null
     *   because it has already opened a synchronous section around the same work.
     */
    fun begin(
        span: Span,
        track: String = TRACK_APP,
        section: String? = span.section,
        cookie: Int = nextCookie(),
    ): OpenSpan {
        section?.let { SystemTrace.beginAsyncSection(it, cookie) }
        kotzilla.mark("${span.id} begin", track)
        return OpenSpan(
            id = span.id,
            section = section,
            cookie = cookie,
            track = track,
            cloud = cloud.start(span.id),
        )
    }

    private fun nextCookie(): Int = cookies.updateAndGet { it + 1 }

    /**
     * A span waiting for whatever closes it, and the Firebase trace it is writing to.
     *
     * [end] reports; [abandon] does not, and that is the difference between "this took 12 seconds"
     * and "this never finished". Abandonment leaves the Firebase trace unstopped, because an
     * unstopped trace is never reported at all — the rule the player's startup trace has always
     * followed, and the reason a span may be dropped without inventing a duration for it.
     *
     * Both are idempotent and safe to call in either order: several callbacks race to close one of
     * these, and the player's first rendered frame arrives after some of the paths that give up.
     *
     * Inner rather than top-level so it reads its owner's sink and clock instead of being handed
     * them — there is no such thing as one of these without the [Metrics] that opened it.
     */
    inner class OpenSpan internal constructor(
        private val id: String,
        private val section: String?,
        private val cookie: Int,
        private val track: String,
        val cloud: CloudTrace,
    ) {

        private val startedAt = elapsedRealtime()
        private val closed = MutableStateFlow(false)

        /** Reports the span, with any [attributes] and [metrics] Firebase should carry. */
        fun end(
            attributes: Map<String, String> = emptyMap(),
            metrics: Map<String, Long> = emptyMap(),
        ) {
            if (!closed.compareAndSet(expect = false, update = true)) return
            closeSection()
            val elapsed = elapsedRealtime() - startedAt
            attributes.forEach { (name, value) -> cloud.attribute(name, value) }
            metrics.forEach { (name, value) -> cloud.metric(name, value) }
            cloud.stop()
            kotzilla.mark("$id end", track)
            kotzilla.log("$id $elapsed ms")
        }

        /** Drops the span. The system section closes; the Firebase trace is never reported. */
        fun abandon() {
            if (!closed.compareAndSet(expect = false, update = true)) return
            closeSection()
            kotzilla.mark("$id abandoned", track)
        }

        /**
         * The system section closes first and unconditionally, before either sink's rules get a
         * say: it is a local measurement with neither a sampling policy nor an abandonment rule,
         * and a section left open is drawn by Perfetto as a slice running to the end of the
         * capture.
         */
        private fun closeSection() {
            section?.let { SystemTrace.endAsyncSection(it, cookie) }
        }
    }

    companion object {

        /** The default Kotzilla track. Areas with spans of their own group under their own name. */
        const val TRACK_APP = "app"
    }
}

/**
 * Measures how long this flow takes to produce its **first** emission after each collection, and
 * how many rows that emission carried.
 *
 * Time to first content is the number a screen is actually judged by, and it is bounded: a list
 * flow re-emits on every database change and a search flow is re-collected on every debounced
 * keystroke, so reporting each emission would flood Kotzilla's timeline and burn the Firebase
 * budget — a device sends at most 300 trace events per 10 minutes, shared with network traces.
 * Emissions after the first pass through untouched; the per-emission system-trace sections the
 * repository writes are unaffected and still record every one of them.
 *
 * Collected but never emitting — a query cancelled by the next keystroke — abandons, so a fast
 * typist leaves abandoned marks rather than half-open Firebase traces.
 *
 * Applied *before* `flowOn` so the mapping it is measuring runs inside the span.
 */
fun <T> Flow<T>.firstContent(
    metrics: Metrics,
    span: Span,
    track: String = Metrics.TRACK_APP,
    rows: (T) -> Int,
): Flow<T> = flow {
    // Per collection, not per flow: the same cold flow can be collected by two screens at once.
    var open: Metrics.OpenSpan? = metrics.begin(span, track = track, section = span.firstSection)
    try {
        collect { value ->
            open?.let { it.end(metrics = mapOf("rows" to rows(value).toLong())) }
            open = null
            emit(value)
        }
    } finally {
        open?.abandon()
    }
}
