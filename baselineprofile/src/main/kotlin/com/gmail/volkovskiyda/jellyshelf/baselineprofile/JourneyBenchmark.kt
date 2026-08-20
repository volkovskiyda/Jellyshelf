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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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
 * Run it by hand with the Pixel 5 attached, awake and unlocked. Like the profile generator beside
 * it, it is not part of any build and CI never invokes it — the app APK is arm64-only, which no
 * x86_64 runner or Gradle-managed device can install:
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
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
 * **It measures whatever state the install is in.** Every test below needs a populated library:
 * [startup] measures a cold launch *into a list*, and the browse section only exists because there
 * are rows to map. Run [BaselineProfileGenerator] first — it signs in, syncs and leaves the app on
 * the Library tab, which is exactly the state these want — or drive the app there by hand. The
 * checks below say so rather than reporting a fast launch into an empty screen as an improvement.
 *
 * **Comparing across versions is manual, and deliberately so.** There is no committed baseline to
 * regress against: these numbers depend on the device, the library size and the thermal state of
 * the phone, so a threshold in the repository would fail for reasons that have nothing to do with a
 * commit. Record a run before a change and one after, on the same phone with the same library, and
 * read the difference — the same discipline `BrowseCostBenchmark` documents for its own numbers.
 */
@RunWith(AndroidJUnit4::class)
class JourneyBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * The app being measured, from the runner argument `:baselineprofile`'s build script fills in
     * off the built APK's own metadata — the same channel [BaselineProfileGenerator] reads, and for
     * the same reason: the profiling variants carry a `.benchmark` suffix, so the shipped
     * application id is not the one installed here.
     */
    private val targetPackage = requireNotNull(
        InstrumentationRegistry.getArguments().getString("targetAppId"),
    ) {
        "targetAppId runner argument missing — it is set in baselineprofile/build.gradle.kts, so " +
            "this run is not the one Gradle configures. Use " +
            "./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest."
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
        awaitLibrary()
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
        val list = awaitLibrary()
        // Keep the gesture off the display edges, which the system back gesture owns.
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
        repeat(SCROLLS) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    /**
     * The library list, once it has rows in it, or a failed run.
     *
     * Throws rather than returning null on purpose: a benchmark that quietly measured a launch onto
     * an empty screen would report a number that looks like an improvement and means nothing. The
     * selector is a resource id published from a Compose test tag by `testTagsAsResourceId` — the
     * same bridge [BaselineProfileGenerator] steers by.
     */
    private fun MacrobenchmarkScope.awaitLibrary(): UiObject2 {
        checkNotNull(device.wait(Until.findObject(By.res(LIBRARY_ROW)), TIMEOUT_MS)) {
            "The library has no rows, so there is nothing here worth timing. Run " +
                "BaselineProfileGenerator first to sign in and sync, or check that " +
                "testTagsAsResourceId is still set on MainActivity's root Scaffold."
        }
        return checkNotNull(device.wait(Until.findObject(By.res(LIBRARY_LIST)), TIMEOUT_MS)) {
            "Library rows exist but the list container does not — the resource-id bridge broke."
        }
    }

    private companion object {
        /**
         * The section names, repeated from `:app`'s `util/Traces.kt`.
         *
         * They have to be repeated: this is a `com.android.test` module targeting `:app`, so it
         * cannot see a single class of it — the same reason [BaselineProfileGenerator] holds its own
         * copies of the resource ids it steers by. That makes these a contract rather than a
         * reference, and a one-sided rename does not fail to compile, it silently measures nothing.
         * `Traces` says so on the other side too.
         */
        const val APP_ON_CREATE = "Jellyshelf.app.onCreate"
        const val START_KOIN = "Jellyshelf.app.startKoin"
        const val ACTIVITY_ON_CREATE = "Jellyshelf.activity.onCreate"
        const val LIBRARY_BROWSE = "Jellyshelf.library.browse"

        /** Resource ids, published from Compose test tags by `testTagsAsResourceId`. */
        const val LIBRARY_LIST = "library_list"
        const val LIBRARY_ROW = "library_row"

        /**
         * Enough for a median to mean something without the phone heating up, which changes the
         * answer more than most code does. Raise it when chasing a small difference, and let the
         * device cool between runs.
         */
        const val ITERATIONS = 5

        const val TIMEOUT_MS = 10_000L
        const val SCROLLS = 3
        const val GESTURE_MARGIN_FRACTION = 5
    }
}
