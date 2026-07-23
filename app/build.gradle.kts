plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

// Reads KEY=VALUE lines from a repo-root env file (blanks/comments ignored); a missing file yields
// an empty map. Feeds the opt-in live-endpoint instrumentation tests via
// testInstrumentationRunnerArguments below, so the values reach the tests as `am instrument -e`
// extras at run time and are never compiled into any app or test BuildConfig. Changing .test.env
// needs no rebuild.
fun loadEnv(file: java.io.File): Map<String, String> =
    file.takeIf { it.exists() }?.readLines()
        ?.mapNotNull { line ->
            line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }
                ?.split("=", limit = 2)?.takeIf { it.size == 2 }
                ?.let { (k, v) -> k.trim() to v.trim() }
        }?.toMap().orEmpty()

val testEnv = loadEnv(rootProject.file(".test.env"))

android {
    namespace = "com.gmail.volkovskiyda.jellyshelf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gmail.volkovskiyda.jellyshelf"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Live-endpoint test config from the git-ignored .test.env, passed as runtime instrumentation
        // extras (never baked into BuildConfig). Blank when the file is absent, so the live tests skip.
        testInstrumentationRunnerArguments += mapOf(
            "jellyfinServerUrl" to testEnv["JELLYFIN_SERVER_URL"].orEmpty(),
            "jellyfinApiKey" to testEnv["JELLYFIN_API_KEY"].orEmpty(),
            "jellyfinIndexUrl" to testEnv["JELLYFIN_INDEX_URL"].orEmpty(),
        )

        // youtubedl-android bundles a Python runtime per ABI. Ship arm64 only — it covers
        // virtually all modern physical devices and keeps the APK from ballooning across ABIs.
        ndk {
            //noinspection ChromeOsAbiSupport -- arm64 only by design; x86_64 would bundle a second Python runtime.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Install debug and release side by side, and change the launcher icon
            // (debug overrides ic_launcher_foreground in src/debug/res with a "d" badge).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinking/obfuscation. Library consumer rules (Ktor, Room, kotlinx-serialization)
            // come in automatically; app-specific rules live in src/main/keepRules/.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Generates BuildConfig.DEBUG so debug/release-only behaviour (HTTP logging, Timber) keys
        // off the build type instead of a runtime FLAG_DEBUGGABLE check.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // youtubedl-android extracts its bundled Python/binaries from the APK at runtime, which
        // requires the native libraries to be stored uncompressed and extractable.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        checkAllWarnings = true
        // The one check checkAllWarnings leaves off (experimental interprocedural analysis).
        enable += "WrongThreadInterprocedural"
        // Baseline (not disable) for the one known third-party false positive: ktor-utils
        // references java.lang.management from IntelliJ-debugger-only code that never runs on
        // Android (InvalidPackage). Baselining keeps the check live for future dependencies.
        // Regenerate after dependency bumps with: ./gradlew :app:updateLintBaselineDebug
        baseline = file("lint-baseline.xml")
        // Also print findings to the console; file reports in build/reports/ stay as-is.
        // Plain File("stdout") (not project file()) — lint only treats the bare name as console.
        textReport = true
        textOutput = File("stdout")
    }
}

ksp {
    // Check generated Room schemas into app/schemas so version bumps can ship real migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    implementation(libs.timber)
    implementation(libs.youtubedl.android.library)
    testImplementation(libs.junit)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.koin.bom))
    androidTestImplementation(platform(libs.ktor.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.koin.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
}
