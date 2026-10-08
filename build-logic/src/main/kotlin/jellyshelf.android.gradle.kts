import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.GeneratesTestApk
import com.android.build.api.variant.TestAndroidComponentsExtension
import jellyshelf.envFile
import org.gradle.api.artifacts.VersionCatalogsExtension

// Everything :app and :baselineprofile configure identically: the SDK levels, the Java level, the
// instrumentation runner and the live-test runner arguments. No plugins block of its own — each
// module applies com.android.application or com.android.test itself, and this plugin configures
// whichever arrives. It is not a replacement for those: namespace, application id, targetSdk, the
// build types and everything module-specific stay in the module's script.

// The catalog by name, as in jellyshelf.ktlint: precompiled script plugins get no `libs` accessor.
// minSdk and compileSdk are shared by both modules on purpose — the macrobenchmark module
// instruments the app it profiles, so both must compile against the same platform and accept the
// same devices. targetSdk is :app's alone (a com.android.test module declares none), so it is
// not read here. See the comment at the top of gradle/libs.versions.toml.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val sdk = { ref: String -> libs.findVersion(ref).get().requiredVersion.toInt() }

// withPlugin, not an eager hasPlugin test: it fires whether the module's AGP plugin was applied
// before or after this one. Both com.android.application and com.android.test apply
// com.android.base, so one hook covers both module kinds.
pluginManager.withPlugin("com.android.base") {
    // Live-endpoint test config from the git-ignored .test.env, passed as runtime instrumentation
    // extras (never baked into BuildConfig, so changing .test.env needs no rebuild). Absent when
    // the file or the key is, so the live tests skip — they read every extra with `orEmpty()`.
    // :baselineprofile's generator reads only the first five; the rest cost it nothing.
    //
    // Absent rather than blank, and this is load-bearing: since AGP 9.4.0 the connected-test
    // engine hands `am instrument` one unquoted shell string, so a blank value turns into
    // `-e jellyfinIndexUrl -e jellyfinSyncFolder …` — the next flag becomes the value, every
    // pair after it shifts, and `am` answers "Invalid userId -2" having run *no* tests, while
    // the Gradle task still reports success. Measured on the API 37 tablet AVD on 2026-09-10,
    // with the two optional keys unset; :baselineprofile hit the same thing on every
    // JourneyBenchmark run while it still passed `.orEmpty()`. Values with whitespace break the
    // same way, which is what the guard below refuses rather than leaves to be diagnosed as
    // "Invalid userId -2" — it is the metadata API token that makes this reachable: unlike every
    // other value here it is an opaque secret, so nothing about it says it may not contain a space.
    //
    // Lazy end to end: the file is read, filtered and guarded only when a connected-test task
    // computes its arguments, through the variant API's MapProperty below. So the guard now fails
    // the builds that would carry a bad value to a device, and no longer every build — a broken
    // .test.env does not stop `assembleDebug`.
    val liveTestArguments = envFile(".test.env").map { testEnv ->
        mapOf(
            "jellyfinServerUrl" to testEnv["JELLYFIN_SERVER_URL"],
            "jellyfinUsername" to testEnv["JELLYFIN_USERNAME"],
            "jellyfinPassword" to testEnv["JELLYFIN_PASSWORD"],
            "jellyfinIndexUrl" to testEnv["JELLYFIN_INDEX_URL"],
            "jellyfinSyncFolder" to testEnv["JELLYFIN_SYNC_FOLDER"],
            "jellyfinSyncFolderId" to testEnv["JELLYFIN_SYNC_FOLDER_ID"],
            "jellyfinTestItemId" to testEnv["JELLYFIN_TEST_ITEM_ID"],
            // The metadata API pair is the one config LiveMetadataApiTest owns: filled, that suite
            // runs; blank, it skips and the live run covers the no-metadata-API path every other
            // live test already exercises. See .example.test.env.
            "jellyfinMetadataApiUrl" to testEnv["JELLYFIN_METADATA_API_URL"],
            "jellyfinMetadataApiToken" to testEnv["JELLYFIN_METADATA_API_TOKEN"],
        ).filterValues { !it.isNullOrBlank() }.mapValues { (_, value) -> value!! }
            .onEach { (key, value) ->
                require(value.none { it.isWhitespace() }) {
                    "$key contains whitespace. It reaches the device on one unquoted `am instrument` " +
                        "command line, where a space shifts every argument after it and the run " +
                        "executes no tests at all — fix the value in .test.env."
                }
            }
    }
    // Every test APK the module builds: the device-test components of an application variant, or
    // the variant itself in a com.android.test module. Both expose instrumentationRunnerArguments as
    // a MapProperty, which takes the provider as is.
    val addLiveTestArguments = { testApk: GeneratesTestApk ->
        testApk.instrumentationRunnerArguments.putAll(liveTestArguments)
    }
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant -> variant.deviceTests.values.forEach(addLiveTestArguments) }
        }
    }
    pluginManager.withPlugin("com.android.test") {
        extensions.configure<TestAndroidComponentsExtension> {
            onVariants(selector().all(), addLiveTestArguments)
        }
    }
    // Through the getters with `apply`: the `defaultConfig { }` and `compileOptions { }` block
    // forms exist only on the ApplicationExtension / TestExtension sub-interfaces (each with its
    // own DefaultConfig type), while CommonExtension — the one type both modules share — has the
    // getters alone.
    extensions.configure<CommonExtension> {
        compileSdk = sdk("compileSdk")

        defaultConfig.apply {
            minSdk = sdk("minSdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
