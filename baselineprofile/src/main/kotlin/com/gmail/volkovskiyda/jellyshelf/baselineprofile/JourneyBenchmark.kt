package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import androidx.benchmark.ExperimentalBenchmarkConfigApi
import androidx.benchmark.ExperimentalConfig
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.perfetto.ExperimentalPerfettoCaptureApi
import androidx.benchmark.perfetto.PerfettoConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app costs, measured on the device rather than argued about — and measured the same way
 * every time, so two app versions produce numbers that can be compared.
 *
 * This is the reading half of `:app`'s `util/Traces.kt`. The app writes named system-trace sections
 * on the paths that matter; [TraceSectionMetric] pulls each one out of the Perfetto trace the
 * benchmark records and reports it beside the framework's own startup and frame metrics. A slice
 * that gets slower shows up as a number here rather than as a vague sense that launches feel worse.
 *
 * Run it by hand with a device attached, awake and unlocked. Like the profile generator beside it,
 * it is not part of any build and CI never invokes it — the app APK is arm64-only, which no x86_64
 * runner or Gradle-managed device can install:
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
 *
 * With more than one device attached that runs on each in turn; `ANDROID_SERIAL` picks one.
 *
 * Results print to the console and land as JSON in
 * `baselineprofile/build/outputs/connected_android_test_additional_output/`, with the Perfetto
 * traces beside them — open one in [ui.perfetto.dev](https://ui.perfetto.dev) to see the sections
 * in context when a number moves and the reason is not obvious.
 *
 * **`benchmarkRelease`, not `debug`.** That variant is R8-minified and signed like the shipped
 * build, carries the committed baseline profile, and installs as `…jellyshelf.benchmark` beside
 * both the release build and `.debug` (see `:app`'s `finalizeDsl`). Measuring a debug build would
 * measure the debug build.
 *
 * **It brings its own library.** Every test here needs a populated one: [startup] measures a cold
 * launch *into a list*, and the browse section only exists because there are rows to map. This used
 * to be the reader's job — the file said to run [BaselineProfileGenerator] first — and that could
 * not be done. The generator's tests are `@skipped` in this variant, because `BaselineProfileRule`
 * only collects in `nonMinifiedRelease`; and AGP's connected-test teardown uninstalls the app under
 * test at the end of every run, so a real generation run's signed-in, synced install is gone before
 * this one starts. [ensureLibrary] is therefore called here too, from [connect] below, and the
 * checks that fail on an empty list are kept as the backstop they always were.
 *
 * **Comparing across versions is manual, and deliberately so.** There is no committed baseline to
 * regress against: these numbers depend on the device, the library size and the thermal state of
 * the phone, so a threshold in the repository would fail for reasons that have nothing to do with a
 * commit. Record a run before a change and one after, on the same device with the same library, and
 * read the difference — the same discipline `BrowseCostBenchmark` documents for its own numbers.
 */
@RunWith(AndroidJUnit4::class)
class JourneyBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * The app being measured, from the runner argument `:baselineprofile`'s build script fills in
     * off the built APK's own metadata — the profiling variants carry a `.benchmark` suffix, so the
     * shipped application id is not the one installed here.
     */
    private val targetPackage = JourneyConfig.targetPackage

    /**
     * Signs in, syncs and leaves the app on a populated Library tab, before anything is measured.
     *
     * Its own [MacrobenchmarkScope] rather than the one `measureRepeated` hands the blocks below:
     * this has to happen once, ahead of the iterations, and outside every trace the run records.
     * Doing it in a `setupBlock` would work but would repeat the whole check on each of the
     * [ITERATIONS] passes for nothing.
     *
     * Cheap on the second call and on every later run against a surviving install — [ensureLibrary]
     * reads the state off the screen and steps past whatever has already happened. The first call
     * after a fresh install pays for a real sign-in and sync, which is the price of the teardown
     * uninstall and not something this can avoid.
     *
     * It ends with [awaitStoppable] because connecting is not free of consequences: a first sync
     * leaves WorkManager holding live work, and work is what brings a force-stopped process back.
     */
    @Before
    fun connect() {
        val scope = MacrobenchmarkScope(targetPackage, launchWithClearTask = true)
        scope.ensureLibrary()
        // Leaves the process gone and staying gone, which [startup] needs and cannot check for
        // itself in a way that says what went wrong.
        scope.awaitStoppable()
    }

    /**
     * A cold launch into the library list.
     *
     * The framework metric answers "how long until something is on screen"; the four sections
     * answer "spent where", and they are the four steps a cold start actually pays for in this
     * app's own code. `Sum` rather than `First` for each: a section that starts being entered twice
     * is itself the regression, and `First` would hide exactly that.
     *
     * [CompilationMode.Partial] with [BaselineProfileMode.Require] is what makes successive runs
     * comparable — and is the only mode that measures what ships. It fails the run rather than
     * quietly measuring an unprofiled app, which is worth having on its own: a broken profile is
     * invisible in every other gate this project has.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun startup() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(
            StartupTimingMetric(),
            TraceSectionMetric(APP_ON_CREATE, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(START_KOIN, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(ACTIVITY_ON_CREATE, TraceSectionMetric.Mode.Sum),
            // The first browse emission, which a launch onto the Library tab always pays.
            TraceSectionMetric(LIBRARY_BROWSE, TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Short, not the sync budget [awaitLibrary] defaults to: [connect] has already established
        // that the rows are there, so anything slower than a launch here is a failure, not a wait.
        awaitLibrary(LIBRARY_TIMEOUT_MS)
    }

    /**
     * Scrolling the library, for the metric the sections cannot give: whether rendering those rows
     * drops frames. Warm rather than cold — the launch is [startup]'s subject, not this one's — and
     * the scroll itself is what is measured.
     *
     * [LIBRARY_BROWSE] is deliberately *not* a metric here. A scroll re-uses the emission the
     * launch already produced; the flow re-emits when the data changes, not when the list moves, so
     * asking for the section would report zero and read as though the cost had vanished.
     */
    @Test
    fun libraryScroll() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        awaitLibrary(LIBRARY_TIMEOUT_MS)
        // The first fling is checked, and only the first: it reports whether the content actually
        // moved, which is the difference between a scroll benchmark and a benchmark of a list
        // sitting still. That second thing is what a gesture the device quietly refused produces,
        // and in the numbers it looks like an improvement. The rest are allowed to run out of list.
        check(flingScreen(Direction.DOWN)) {
            "The library did not scroll, so there are no frames here worth timing. Either the " +
                "gesture never reached the list, or the library is short enough to fit on this " +
                "screen, which no benchmark can make scrollable."
        }
        repeat(SCROLLS - 1) { flingScreen(Direction.DOWN) }
    }

    /**
     * Opening a video and advancing to the next one, which is the whole of what this app's playback
     * performance is: a tap to the first frame, and a queue advance to the next first frame.
     *
     * Three sections, and they answer different questions. [PLAYER_RESOLVE] is our own work — a
     * settings read and a Room read per item — and is the only one that is not mostly waiting.
     * [PLAYER_STARTUP] is the whole tap-to-first-frame window `player_startup` reports in the
     * field. [PLAYER_TRANSITION] is the queue advance, and is the only one an ExoPlayer flag about
     * per-stream media progression can move; measuring startup alone would return a clean null
     * result and be believed.
     *
     * **Live and demo numbers are not comparable to each other.** With a filled `.test.env` this
     * streams from a real Jellyfin over the network; without one it reads a bundled `asset:` clip
     * with no network at all, which is a different measurement wearing the same name. Compare a
     * number only against another taken in the same mode, on the same device, in the same sitting.
     *
     * Cold, like [startup], because a queue's first item is a cold start in practice — the user
     * came from the library, not from another video.
     */
    @OptIn(
        ExperimentalMetricApi::class,
        ExperimentalBenchmarkConfigApi::class,
        ExperimentalPerfettoCaptureApi::class,
    )
    @Test
    fun playback() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(
            TraceSectionMetric(PLAYER_STARTUP, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(PLAYER_RESOLVE, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(PLAYER_TRANSITION, TraceSectionMetric.Mode.Sum),
        ),
        iterations = ITERATIONS,
        experimentalConfig = ExperimentalConfig(perfettoConfig = playbackTraceConfig()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        awaitLibrary(LIBRARY_TIMEOUT_MS)
        openFirstVideoDetails()

        // The same reach-then-settle-then-reach the generator documents: the thumbnail above the
        // button loads while this screen is being read, and the layout it settles into moves
        // everything below it, so a tap aimed at where the button was lands on nothing.
        scrollTo(By.text(PLAY)) { "The detail screen never offered playback." }
        awaitContentStill()
        await(By.text(PLAY), TIMEOUT_MS) {
            "The Play button left the detail screen while it was being reached for."
        }.click()
        awaitPlaybackUnderway()

        advanceToNextItem()

        // Back out to the library before the iteration ends, which is not tidiness: the app is
        // otherwise left sitting in the player, and the next iteration's cold start does not land
        // on the library — its [awaitLibrary] then fails and takes the whole run with it. Measured
        // on a Pixel 5: iteration 1 passed, iteration 2 died on exactly that.
        //
        // Inside the measured block rather than in [setupBlock] because setupBlock runs before the
        // process kill, and because the three metrics here are named trace sections — work after
        // the last of them closes costs wall-clock, not accuracy. Backing out also stops playback,
        // which is what keeps a foreground media session from surviving into the next iteration.
        returnToTopLevel()
    }

    /**
     * Drives one queue advance with the next button, and waits for the new item to really be
     * playing before the measurement ends.
     *
     * Driven explicitly rather than by waiting out a whole video, for the obvious reason.
     *
     * The enabled check is what stops a queue of one from silently measuring nothing. The button
     * dims at the end of the queue, so on a single-item queue the tap would land on a dead control,
     * [PLAYER_TRANSITION] would never open, and the metric would report zero — which reads as
     * "free" rather than as "never happened". Failing loudly here is the whole point of the check;
     * per this benchmark's own rule, a zero is to be chased, not recorded.
     *
     * The controls hide three seconds into playing, so the button usually is not on screen when
     * this arrives. One tap to reveal them, never a loop: two in quick succession are a double tap,
     * which seeks.
     */
    private fun MacrobenchmarkScope.advanceToNextItem() {
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        val next = device.wait(Until.findObject(By.desc(NEXT_VIDEO)), TIMEOUT_MS)
        checkNotNull(next) {
            "The player never offered a next button, so there is no transition here to measure."
        }
        check(next.isEnabled) {
            "The next button is disabled, so this queue holds one video. A transition benchmark " +
                "needs a row that opens as a multi-video queue — check the library has more than " +
                "one video and that the row was opened rather than a single video played directly."
        }
        next.click()

        // The advance is not the measurement; the new item's first frame is. Reuse the same
        // position-label signal the first item was waited on with.
        awaitPlaybackUnderway()
    }

    /**
     * The Perfetto config [playback] records with, replacing Macrobenchmark's default.
     *
     * **Without this the benchmark reports zero for all three sections, and the zero is a lie.**
     * The default config stops delivering data about 0.7 s into the measured block: the session
     * itself stays up — it goes on asking for flushes — but its data stops reaching the file, and
     * the trace ends with Macrobenchmark's own `measureBlock` slice still open (`dur = -1`). A
     * launch fits in that window, which is why [startup] and [libraryScroll] were never affected
     * and why this went unnoticed until something was measured 30 s in. Measured on a Pixel 5
     * (API 34) on 2026-08-27: `traced_flushes_requested = 6`, `traced_flushes_failed = 4`,
     * 572 chunks discarded, and every app slice inside the first 523 ms.
     *
     * Two differences from the default, both taken from a hand-driven `adb shell perfetto` capture
     * that recorded the same APK on the same device for 45 s without losing anything:
     *
     *  - **One large in-memory ring buffer, and no `write_into_file`.** The default dumps to file
     *    every 2.5 s and flushes every 5 s, and it is those periodic flushes that fail here. A
     *    buffer big enough to hold the whole block removes the need for them, and with none
     *    requested none can fail. The measured block runs about 10 s and a 45 s hand capture of
     *    the same journey came to 46 MB, so 256 MB is several times the headroom needed.
     *  - **Named apps rather than `atrace_apps: "*"`.** The default asks the framework to enable
     *    app tracing for every process on the device; only two matter here, and asking for two is
     *    the shape that was verified working.
     *
     * The data sources are the minimum [TraceSectionMetric] needs. It matches slices by name and
     * filters them by process, so app atrace sections plus process names is the whole requirement
     * — it never reads `sched`, and it does not restrict itself to the measured window either.
     * That last part is why playback must stay in the measure block rather than move to
     * `setupBlock`: sections written during setup would be counted just the same.
     *
     * With this config the same run reports `measureBlock` closed at 10.0 s and all three sections
     * present, `Count = 1` each — against a `dur = -1` block and three zeros without it.
     *
     * One cosmetic cost: dropping the `sched` events means late-created threads never get named, so
     * the app's `ExoPlayer:*` threads do not appear by name in these traces. Nothing here reads
     * thread names; add `sched/sched_switch` back if a future metric does.
     *
     * Keep this in sync with nothing — it is deliberately standalone. If a future Macrobenchmark
     * fixes the flush failure, delete this and the [ExperimentalConfig] argument with it, and
     * confirm the sections still report non-zero.
     */
    @OptIn(ExperimentalPerfettoCaptureApi::class)
    private fun playbackTraceConfig(): PerfettoConfig {
        val instrumentation = InstrumentationRegistry.getInstrumentation().context.packageName
        return PerfettoConfig.Text(
            """
            buffers {
                size_kb: 262144
                fill_policy: RING_BUFFER
            }
            data_sources {
                config {
                    name: "linux.ftrace"
                    ftrace_config {
                        ftrace_events: "task/task_newtask"
                        ftrace_events: "task/task_rename"
                        ftrace_events: "sched/sched_process_exit"
                        ftrace_events: "sched/sched_process_free"
                        atrace_categories: "am"
                        atrace_categories: "view"
                        atrace_categories: "wm"
                        atrace_apps: "$targetPackage"
                        atrace_apps: "$instrumentation"
                    }
                }
            }
            data_sources {
                config {
                    name: "linux.process_stats"
                    process_stats_config {
                        scan_all_processes_on_start: true
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private companion object {
        /**
         * The section names, repeated from `:app`'s `util/Traces.kt`.
         *
         * They have to be repeated: this is a `com.android.test` module targeting `:app`, so it
         * cannot see a single class of it — the same reason [LibraryJourney.kt][JourneyConfig] holds
         * its own copies of the resource ids it steers by. That makes these a contract rather than a
         * reference, and a one-sided rename does not fail to compile, it silently measures nothing.
         * `Traces` says so on the other side too.
         *
         * That applies to the three player sections as much as to the launch ones — more so, since
         * [playback] is the only thing that reads them and a zero there is indistinguishable from a
         * fast transition until someone opens the trace.
         */
        const val APP_ON_CREATE = "Jellyshelf.app.onCreate"
        const val START_KOIN = "Jellyshelf.app.startKoin"
        const val ACTIVITY_ON_CREATE = "Jellyshelf.activity.onCreate"
        const val LIBRARY_BROWSE = "Jellyshelf.library.browse"
        const val PLAYER_RESOLVE = "Jellyshelf.player.resolve"
        const val PLAYER_STARTUP = "Jellyshelf.player.startup"
        const val PLAYER_TRANSITION = "Jellyshelf.player.transition"

        /**
         * Enough for a median to mean something without the device heating up, which changes the
         * answer more than most code does. Raise it when chasing a small difference, and let the
         * device cool between runs.
         */
        const val ITERATIONS = 5

        /** Long enough for a launch to render its first rows, short enough to fail fast. */
        const val LIBRARY_TIMEOUT_MS = 10_000L
    }
}
