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
        // into any BuildConfig, so changing .test.env needs no rebuild. Blank when the file is
        // absent, which is what makes the real-server journey skip.
        testInstrumentationRunnerArguments += mapOf(
            "jellyfinServerUrl" to testEnv["JELLYFIN_SERVER_URL"].orEmpty(),
            "jellyfinUsername" to testEnv["JELLYFIN_USERNAME"].orEmpty(),
            "jellyfinPassword" to testEnv["JELLYFIN_PASSWORD"].orEmpty(),
            "jellyfinIndexUrl" to testEnv["JELLYFIN_INDEX_URL"].orEmpty(),
            "jellyfinSyncFolder" to testEnv["JELLYFIN_SYNC_FOLDER"].orEmpty(),
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    targetProjectPath = ":app"
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
