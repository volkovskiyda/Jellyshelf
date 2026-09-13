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
import org.junit.After
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
            // And the wait that contains it: collecting the flow through to those rows arriving.
            TraceSectionMetric(LIBRARY_FIRST, TraceSectionMetric.Mode.Sum),
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
     * The Categories tab: listing it, narrowing it, and opening one category's videos.
     *
     * Three waits the user actually sits through, and the only journey that produces any of them.
     * [CATEGORIES_LIST_FIRST] is the tab's own list, whose per-row video count is a correlated
     * subquery — it grows with the number of categories *and* with the library behind them.
     * [CATEGORIES_SEARCH_FIRST] is the most expensive thing this tab can be asked to do: the query
     * under it scans every video's description with `LIKE '%…%'`, on every debounced keystroke.
     * [CATEGORY_VIDEOS_FIRST] is one category's videos, which until the spans were split was
     * indistinguishable from the library's own list.
     *
     * **The order is load-bearing.** Search narrows the list and then the first *match* is opened,
     * so the tab is entered once and each section fires exactly once. Pressing back to the list
     * instead would re-compose the tab, re-collect its flow, and add a second list load to a `Sum`
     * that reads as a regression.
     *
     * Warm rather than cold: a cold launch is [startup]'s subject, and what is being timed here is
     * three queries, not a process start. That choice is what the setup has to undo. A warm start
     * recreates the Activity but keeps the **process**, so `CategoriesFilterState` — a Koin single —
     * carries the previous iteration's search query across it, and the tab would open already
     * narrowed: the browse path never runs and [CATEGORIES_LIST_FIRST] reports zero from the second
     * iteration on. Measured that way before the clear was added: count 1 on the first iteration and
     * 0 on the rest, for a median of 0, which reads as "the list is free" rather than "the list was
     * never loaded". The setup also walks back to the Library tab, because each iteration ends
     * inside a category and the back stack is persisted.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun categories() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(
            TraceSectionMetric(CATEGORIES_LIST_FIRST, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(CATEGORIES_SEARCH_FIRST, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(CATEGORY_VIDEOS_FIRST, TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            returnToTopLevel()
            // Both of these are undone state, not setup for its own sake: the query survives a warm
            // start in a process-lifetime single, and the back stack reopens inside a category.
            openTab(TAB_CATEGORIES)
            setText(CATEGORY_SEARCH, "")
            openTab(TAB_LIBRARY)
            awaitLibrary(LIBRARY_TIMEOUT_MS)
        },
    ) {
        openTab(TAB_CATEGORIES)
        awaitCategories()
        searchCategories()
        openFirstCategory()
    }

    /**
     * Opening a video and advancing to the next one, which is the whole of what this app's playback
     * performance is: a tap to the first frame, and a queue advance to the next first frame.
     *
     * Four sections, and they answer different questions. [PLAYER_RESOLVE] is our own work — a
     * settings read and a Room read per item — and is the only one that is not mostly waiting.
     * [PLAYER_STARTUP] is the whole tap-to-first-frame window `player_startup` reports in the
     * field. [PLAYER_TRANSITION] is the queue advance, and is the only one an ExoPlayer flag about
     * per-stream media progression can move; measuring startup alone would return a clean null
     * result and be believed. [PLAYER_SEEK] is a scrub inside the item already playing, which is
     * the one wait here that no call site in the app can see — the seek bar fires its own `seekTo`
     * from inside media3, so only the player's position discontinuity reports it.
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
            TraceSectionMetric(PLAYER_SEEK, TraceSectionMetric.Mode.Sum),
        ),
        iterations = ITERATIONS,
        experimentalConfig = ExperimentalConfig(perfettoConfig = playbackTraceConfig()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        awaitLibrary(LIBRARY_TIMEOUT_MS)
        startFirstVideo()

        // Before the advance, and backwards: see [scrubBackward] for why the direction is not a
        // preference. It is the only seek any journey here performs, so [PLAYER_SEEK] is measured
        // nowhere else.
        scrubBackward()

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
        // Backing out starts the stop; this is where the iteration waits for the app to say it
        // finished. It reduces — but does not eliminate — the next iteration failing its cold-start
        // check on a process that came back. See [awaitPlaybackStopped] for what was measured.
        awaitPlaybackStopped()
    }

    /**
     * Scrolling the library while a video plays on in the mini-player bar — [libraryScroll] with
     * everything the bar adds: a video decoding in the background, a service ticking its position
     * once a second, and a progress line redrawn on every tick under the list being flung. The
     * number to read is the delta against [libraryScroll]: the two fling the same list the same
     * way, so anything this one loses is what live playback plus the ticking bar cost the scroll.
     *
     * No `startupMode` and no kill between iterations, deliberately: playback has to survive from
     * one iteration to the next for the bar to be on screen at all, and the launch is [startup]'s
     * subject. The setup is state-driven the way [ensureLibrary] is — the first iteration pays for
     * starting and minimizing playback, the later ones find the bar already there and only rewind
     * the list. A queue that runs out mid-run takes the bar with it, and the same check then
     * restarts playback rather than timing a bare list under the wrong name.
     */
    @Test
    fun libraryScrollDuringPlayback() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = ITERATIONS,
        setupBlock = {
            if (!device.hasObject(By.desc(MINI_PLAYER_STOP))) {
                pressHome()
                startActivityAndWait()
                awaitLibrary(LIBRARY_TIMEOUT_MS)
                startFirstVideo()
                minimizePlayer()
            }
            // Back to the top, so every iteration has the same list below it to fling through —
            // without this the run drifts toward the bottom and the later iterations time a list
            // with nowhere left to go.
            sweepToEnd(Direction.UP)
        },
    ) {
        check(flingScreen(Direction.DOWN)) {
            "The library did not scroll under the mini-player bar, so there are no frames here " +
                "worth timing — see libraryScroll for what a refused gesture reads as."
        }
        repeat(SCROLLS - 1) { flingScreen(Direction.DOWN) }
    }

    /**
     * [libraryScrollDuringPlayback] leaves its video playing on purpose — stopping it is no part
     * of any iteration, and the bar must survive between them. Stopped here instead, so a finished
     * run does not walk away from a live media session holding a foreground service and a stream.
     * A no-op after every other test: [awaitPlaybackStopped] returns quietly when no bar is up.
     */
    @After
    fun stopLingeringPlayback() {
        val scope = MacrobenchmarkScope(targetPackage, launchWithClearTask = true)
        scope.device.findObject(By.desc(MINI_PLAYER_STOP))?.click()
        scope.awaitPlaybackStopped()
    }

    /**
     * Opens the first video's details and gets it really playing — the tap half of [playback],
     * shared with [libraryScrollDuringPlayback], which needs the same state without the metrics.
     */
    private fun MacrobenchmarkScope.startFirstVideo() {
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
    }

    /**
     * Leaves the player for the library with the session alive, through the top bar's minimize
     * button — the one affordance that does that, since back is the stop sequence.
     *
     * The controls hide three seconds into playing, so the button may or may not be on screen
     * when this arrives — and [awaitPlaybackUnderway] can return inside that window, with them
     * still up. Ask first: a reveal tap thrown while they are up would *hide* them instead. When
     * they are hidden, one tap brings them back — one, never a loop: two in quick succession are
     * a double tap, which seeks.
     *
     * Minimizing lands where the player was opened from — the detail screen — so the walk back to
     * the library is explicit rather than assumed.
     */
    private fun MacrobenchmarkScope.minimizePlayer() {
        if (!device.hasObject(By.desc(MINIMIZE_PLAYER))) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        val minimize = device.wait(Until.findObject(By.desc(MINIMIZE_PLAYER)), TIMEOUT_MS)
        checkNotNull(minimize) {
            "The player never offered its minimize button, with or without a reveal tap."
        }
        minimize.click()
        check(device.wait(Until.hasObject(By.desc(MINI_PLAYER_STOP)), TIMEOUT_MS)) {
            "Minimizing the player never raised the mini-player bar."
        }
        returnToTopLevel()
        openTab(TAB_LIBRARY)
        awaitLibrary(LIBRARY_TIMEOUT_MS)
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
     * The controls hide three seconds into playing, so the button is often off screen when this
     * arrives — but not always: [awaitPlaybackUnderway] returns the moment the position label
     * moves, which is *inside* that three-second window, with the controls still up. Ask first,
     * exactly as [minimizePlayer] does, because the blind "reveal" tap lands dead centre — on the
     * play/pause button — pausing the very playback the advance is about to measure: the next
     * button then advances to an item that loads paused, and the wait for its first frame either
     * burns its timeout or passes vacuously off a seeded resume position. When the controls are
     * hidden, one tap, never a loop: two in quick succession are a double tap, which seeks.
     */
    private fun MacrobenchmarkScope.advanceToNextItem() {
        if (!device.hasObject(By.desc(NEXT_VIDEO))) {
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
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
         * A launch to the first library rows being ready: the whole point of the baseline profile,
         * as one number. `LIBRARY_BROWSE` above times one emission's mapping; this times from the
         * flow being collected to the first emission arriving, which is what the user waits for.
         *
         * The `.first` suffix is not decoration — `Metrics.firstContent` opens a section under that
         * name precisely so this measurement cannot be summed into `LIBRARY_BROWSE` and inflate it.
         */
        const val LIBRARY_FIRST = "Jellyshelf.library.browse.first"

        /** A seek to the frame it produces, measured by [playback]'s scrub. */
        const val PLAYER_SEEK = "Jellyshelf.player.seek"

        /**
         * The three waits [categories] measures, all of them first-content sections rather than
         * per-emission ones — what the user sits through, not what one mapping costs.
         */
        const val CATEGORIES_LIST_FIRST = "Jellyshelf.categories.list.first"
        const val CATEGORIES_SEARCH_FIRST = "Jellyshelf.categories.search.first"
        const val CATEGORY_VIDEOS_FIRST = "Jellyshelf.category.videos.first"

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
 * Top-level rather than a member for the same reason the journey's own helpers are: the class sits
 * at detekt's function threshold, and this needs nothing from it but the package name.
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
                    atrace_apps: "${JourneyConfig.targetPackage}"
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
