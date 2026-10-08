plugins {
    alias(libs.plugins.android.test)
    // SDK levels, Java level, runner and the live-test runner arguments, shared with :app.
    id("jellyshelf.android")
    alias(libs.plugins.androidx.baselineprofile)
    id("jellyshelf.ktlint")
}

android {
    namespace = "com.gmail.volkovskiyda.jellyshelf.baselineprofile"
    // compileSdk, minSdk, the runner, Java 11 and the .test.env runner arguments come from
    // jellyshelf.android. The generator's real-server journey reads five of those arguments and
    // skips itself when they are absent; the other four (:app's test item and metadata API config)
    // arrive too and cost nothing — every test reads each extra with `orEmpty()`. The whitespace
    // guard now applies here as well, which this module never had on its own. They are added
    // through the variant API, so they sit beside the class and targetAppId arguments below.
    targetProjectPath = ":app"
}

// Which app to profile. Since :app's finalizeDsl gives the profiling variants a `.benchmark`
// suffix, that is no longer the shipped application id, and BaselineProfileGenerator reads it from
// this argument instead of holding a copy — the same channel jellyshelf.android's .test.env values use.
//
// The id comes off the built APK's own metadata rather than from `TestVariant.testedApplicationId`,
// which reports *this* module's id (`…jellyshelf.baselineprofile`) and would send the generator
// looking for an app that is not installed. This is how the baseline-profile plugin itself derives
// the `androidx.benchmark.targetPackageName` argument it passes alongside, so the two agree by
// construction; reading that one instead would mean depending on another plugin's internals.
//
// The profiling variant also runs the generator alone. JourneyBenchmark shares the module, and its
// MacrobenchmarkRule skips itself outside benchmarkRelease with an `assumeTrue` — which the AGP 9.4.1
// connected-test engine counts as a failure. That is not the only thing it counts: with the five
// skips gone the task still fails over three passing generator tests, so the profile is generated
// through scripts/generate-baseline-profile.sh rather than the plugin's one-liner (2026-10-07).
// Naming a class on the command line still wins, as in :app.
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "nonMinifiedRelease") {
            // The command-line class when one is named, the generator otherwise — a provider, so
            // nothing is decided before the task runs. Putting the command-line value back is a
            // no-op: it is the value AGP would have passed anyway.
            variant.instrumentationRunnerArguments.put(
                "class",
                providers.gradleProperty("android.testInstrumentationRunnerArguments.class")
                    .orElse("com.gmail.volkovskiyda.jellyshelf.baselineprofile.BaselineProfileGenerator"),
            )
        }
        val builtArtifacts = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { apks ->
                requireNotNull(builtArtifacts.load(apks)?.applicationId) {
                    "No APK metadata under $apks — the app under test was not built."
                }
            },
        )
    }
}

baselineProfile {
    // A physical device, never a managed one: the app APK is arm64-only (youtubedl-android bundles
    // a Python runtime per ABI), so the x86_64 emulator images Gradle-managed devices use cannot
    // install it. This is the same constraint that sends item 06's instrumented suite to Firebase
    // Test Lab. Generation therefore runs over adb against the connected Pixel 5.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
