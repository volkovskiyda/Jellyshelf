plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Same loadEnv as :app (see app/build.gradle.kts): the generator's real-server journey reads the
// live-test credentials from the git-ignored .test.env and skips itself when they are absent.
fun loadEnv(file: java.io.File): Map<String, String> =
    file.takeIf { it.exists() }?.readLines()
        ?.mapNotNull { line ->
            line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }
                ?.split("=", limit = 2)?.takeIf { it.size == 2 }
                ?.let { (k, v) -> k.trim() to v.trim() }
        }?.toMap().orEmpty()

val testEnv = loadEnv(rootProject.file(".test.env"))

android {
    namespace = "com.gmail.volkovskiyda.jellyshelf.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Runtime `am instrument -e` extras, exactly like :app's live-endpoint tests — never baked
        // into any BuildConfig, so changing .test.env needs no rebuild. Missing when the file or
        // the key is, which is what makes the real-server journey skip: the tests read each extra
        // with `orEmpty()` themselves.
        //
        // Omitted rather than passed blank, for the same load-bearing reason :app documents at
        // length: since AGP 9.4.0 the connected-test engine hands `am instrument` one unquoted
        // shell string, so a blank value collapses into `-e jellyfinIndexUrl -e jellyfinSyncFolder
        // …`, every pair after it shifts, and `am` answers "Invalid userId -2" having run no tests
        // at all. This module kept `.orEmpty()` when :app was fixed, and with JELLYFIN_INDEX_URL
        // unset that is precisely what every JourneyBenchmark run hit.
        testInstrumentationRunnerArguments += mapOf(
            "jellyfinServerUrl" to testEnv["JELLYFIN_SERVER_URL"],
            "jellyfinUsername" to testEnv["JELLYFIN_USERNAME"],
            "jellyfinPassword" to testEnv["JELLYFIN_PASSWORD"],
            "jellyfinIndexUrl" to testEnv["JELLYFIN_INDEX_URL"],
            "jellyfinSyncFolder" to testEnv["JELLYFIN_SYNC_FOLDER"],
        ).filterValues { !it.isNullOrBlank() }.mapValues { (_, value) -> value!! }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    targetProjectPath = ":app"
}

// Which app to profile. Since :app's finalizeDsl gives the profiling variants a `.benchmark`
// suffix, that is no longer the shipped application id, and BaselineProfileGenerator reads it from
// this argument instead of holding a copy — the same channel the .test.env values above use.
//
// The id comes off the built APK's own metadata rather than from `TestVariant.testedApplicationId`,
// which reports *this* module's id (`…jellyshelf.baselineprofile`) and would send the generator
// looking for an app that is not installed. This is how the baseline-profile plugin itself derives
// the `androidx.benchmark.targetPackageName` argument it passes alongside, so the two agree by
// construction; reading that one instead would mean depending on another plugin's internals.
androidComponents {
    onVariants { variant ->
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
