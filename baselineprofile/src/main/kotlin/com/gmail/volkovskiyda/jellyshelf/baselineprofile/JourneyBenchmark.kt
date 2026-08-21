package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
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

    private companion object {
        /**
         * The section names, repeated from `:app`'s `util/Traces.kt`.
         *
         * They have to be repeated: this is a `com.android.test` module targeting `:app`, so it
         * cannot see a single class of it — the same reason [LibraryJourney.kt][JourneyConfig] holds
         * its own copies of the resource ids it steers by. That makes these a contract rather than a
         * reference, and a one-sided rename does not fail to compile, it silently measures nothing.
         * `Traces` says so on the other side too.
         */
        const val APP_ON_CREATE = "Jellyshelf.app.onCreate"
        const val START_KOIN = "Jellyshelf.app.startKoin"
        const val ACTIVITY_ON_CREATE = "Jellyshelf.activity.onCreate"
        const val LIBRARY_BROWSE = "Jellyshelf.library.browse"

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
