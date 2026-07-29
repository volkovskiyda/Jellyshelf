import java.time.LocalDateTime

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.compose.screenshot)
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
    // Enables the screenshotTest source set (paired with the same flag in gradle.properties).
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    // Shared test fakes. Only the `test` source set can be extended this way: the
    // screenshot plugin (alpha) registers no AndroidSourceSet of its own — neither
    // "screenshotTest" nor "screenshotTestDebug" exists in this container — so its copy of the
    // fake lives in src/screenshotTest/kotlin instead. Collapse the two when the plugin exposes
    // its source set.
    sourceSets {
        getByName("test") { kotlin.directories += "src/testShared/kotlin" }
        // Instrumented tests share them too — the sync suite needs the same in-memory
        // SettingsRepository the host-side tests use.
        getByName("androidTest") { kotlin.directories += "src/testShared/kotlin" }
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
        printTextReport = true
    }
}

ksp {
    // Check generated Room schemas into app/schemas so version bumps can ship real migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Instrumented tests (Compose behavior tests, Room DAO, Ktor serialization) need a real device or
// emulator. Rather than failing a build that has none — CI without an emulator job, a laptop with
// nothing plugged in — the connected* tasks skip themselves and say so. Attach a device and the
// same command runs them for real.
val adbPath: Provider<String> = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .map { "$it/platform-tools/adb" }
    .orElse("adb")

tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }
    .configureEach {
        // Resolved at configuration time (a declared build input); the probe itself runs in the
        // onlyIf predicate, i.e. at execution time, so no build ever shells out to adb needlessly.
        val adb = adbPath.get()
        onlyIf {
            val attached = runCatching {
                ProcessBuilder(adb, "devices").redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readLines()
                    .drop(1) // "List of devices attached"
                    // Ignore "offline" and "unauthorized" — neither can run a test.
                    .any { it.split(Regex("\\s+")).getOrNull(1) == "device" }
            }.getOrDefault(false)
            if (!attached) {
                logger.lifecycle("No device or emulator attached — skipping $name.")
            }
            attached
        }
    }

/**
 * Single HTML page summarising every test layer and both static-analysis tools, written to
 * `app/build/test-summary/index.html`. Only reads XML that is already on disk — it never runs a
 * test or a check itself, so it is safe to attach to any pipeline; `scripts/run-tests.sh` calls it
 * once the layers it ran have finished.
 *
 * Everything lives inside doLast: the configuration cache cannot serialize references to
 * build-script-level functions or classes, so the parsers are local lambdas rather than helpers.
 */
tasks.register("testSummary") {
    group = "verification"
    description =
        "Aggregate test results (unit, screenshot, instrumented) and static analysis " +
            "(detekt, lint) into one HTML report."
    // Paths resolved at configuration time; all file access happens in doLast.
    val buildDir = layout.buildDirectory.get().asFile
    // detekt is applied to the root project (it scans app/src from there), so its reports land in
    // the root build directory, not this module's.
    val rootBuildDir = rootProject.layout.buildDirectory.get().asFile
    val outFile = buildDir.resolve("test-summary/index.html")
    val layers = listOf(
        Triple("Unit tests", "test-results/testDebugUnitTest", "reports/tests/testDebugUnitTest/index.html"),
        // The screenshot plugin writes per-class HTML (with reference/actual/diff images on a
        // failure) rather than an index.html, so the layer links to that directory's entry page.
        Triple(
            "Screenshot goldens",
            "test-results/validateDebugScreenshotTest",
            "reports/screenshotTest/preview/debug/com.gmail.volkovskiyda.jellyshelf.ui.html",
        ),
        Triple(
            "Behavior tests (device)",
            "outputs/androidTest-results/connected",
            "reports/androidTests/connected/debug/index.html",
        ),
    ).map { (label, results, report) -> Triple(label, buildDir.resolve(results), buildDir.resolve(report)) }
    // Static analysis: findings rather than tests, so these get their own table. Both tools write
    // an XML report next to the HTML one a human opens.
    val checks = listOf(
        Triple(
            "detekt",
            rootBuildDir.resolve("reports/detekt/detekt.xml"),
            rootBuildDir.resolve("reports/detekt/detekt.html"),
        ),
        // lintDebug only, matching scripts/run-tests.sh — the release variant reports the same
        // findings a second time.
        Triple(
            "Android lint (debug)",
            buildDir.resolve("reports/lint-results-debug.xml"),
            buildDir.resolve("reports/lint-results-debug.html"),
        ),
    )
    outputs.upToDateWhen { false }

    doLast {
        val summaryDir = outFile.parentFile

        // Sums the testsuite header attributes across a layer's JUnit XML; null when the layer
        // never ran, so the report can say "not run" rather than "0 passed" — a layer skipped for
        // want of a device is not the same as a layer with nothing in it.
        //
        // Attribute-level string parsing rather than a real XML parse: these files are
        // machine-written and only the header is needed. Both shapes appear — Gradle writes one
        // <testsuite> per class, the instrumentation runner wraps them in a <testsuites> whose
        // totals would double-count, so only the first such element per file is read.
        val parse = { dir: File ->
            val files = dir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
            if (files.isEmpty()) {
                null
            } else {
                var tests = 0
                var failures = 0
                var skipped = 0
                files.forEach { file ->
                    val header = file.readLines().firstOrNull { it.contains("<testsuite") }
                    if (header != null) {
                        val attr = { name: String ->
                            Regex("""$name="(\d+)"""").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        }
                        tests += attr("tests")
                        failures += attr("failures") + attr("errors")
                        skipped += attr("skipped")
                    }
                }
                Triple(tests, failures, skipped)
            }
        }

        // Counts findings by severity in a detekt (checkstyle `<error>`) or lint (`<issue>`) report;
        // null when the file is absent, i.e. the tool never ran. A real XML parse here rather than
        // the line scan above: lint embeds multi-line rule explanations in its attributes, which
        // regexes read wrong. Unreadable XML counts as "not run" instead of failing the summary.
        val parseFindings = { file: File ->
            file.takeIf { it.isFile }?.let { xml ->
                runCatching {
                    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(xml)
                    val severities = listOf("error", "issue").flatMap { tag ->
                        val nodes = doc.getElementsByTagName(tag)
                        (0 until nodes.length).map { i ->
                            (nodes.item(i) as org.w3c.dom.Element).getAttribute("severity").lowercase()
                        }
                    }
                    // detekt emits error/warning/info, lint fatal/error/warning/information/hint;
                    // everything below a warning is folded into "other" (lint's baseline note lands
                    // there, for one).
                    Triple(
                        severities.count { it == "error" || it == "fatal" },
                        severities.count { it == "warning" },
                        severities.count { it != "error" && it != "fatal" && it != "warning" },
                    )
                }.getOrNull()
            }
        }

        // Both tables link to the tool's own report when it is on disk, as a path relative to this
        // page so the whole build directory stays movable.
        val href = { report: File ->
            report.takeIf { it.exists() }
                ?.relativeToOrNull(summaryDir)?.path?.replace(File.separatorChar, '/')
        }
        val parsed = layers.map { (label, resultsDir, report) ->
            Triple(label, parse(resultsDir), href(report))
        }
        val checked = checks.map { (label, resultsFile, report) ->
            Triple(label, parseFindings(resultsFile), href(report))
        }
        val ran = parsed.mapNotNull { it.second }
        val analysed = checked.mapNotNull { it.second }
        if (ran.isEmpty() && analysed.isEmpty()) {
            logger.lifecycle("testSummary: no results found — run scripts/run-tests.sh first.")
            return@doLast
        }
        val totalTests = ran.sumOf { it.first }
        val totalFailures = ran.sumOf { it.second }
        val totalSkipped = ran.sumOf { it.third }
        val rows = parsed.joinToString("\n") { (label, stats, href) ->
            if (stats == null) {
                """      <tr class="notrun"><td>$label</td><td colspan="4">not run</td></tr>"""
            } else {
                val (tests, failures, skipped) = stats
                val name = href?.let { """<a href="$it">$label</a>""" } ?: label
                val cls = if (failures > 0) "fail" else "pass"
                """      <tr class="$cls"><td>$name</td><td>$tests</td>""" +
                    """<td>${tests - failures - skipped}</td><td>$failures</td><td>$skipped</td></tr>"""
            }
        }
        val analysisErrors = analysed.sumOf { it.first }
        val analysisFindings = analysed.sumOf { it.first + it.second + it.third }
        val analysisRows = checked.joinToString("\n") { (label, stats, link) ->
            if (stats == null) {
                """      <tr class="notrun"><td>$label</td><td colspan="4">not run</td></tr>"""
            } else {
                val (errors, warnings, other) = stats
                val name = link?.let { """<a href="$it">$label</a>""" } ?: label
                // Warnings get their own state: detekt fails the build on any finding while lint
                // only fails on errors, so "issues but no errors" is neither a pass nor a failure.
                val cls = when {
                    errors > 0 -> "fail"
                    errors + warnings + other > 0 -> "warn"
                    else -> "pass"
                }
                """      <tr class="$cls"><td>$name</td><td>${errors + warnings + other}</td>""" +
                    """<td>$errors</td><td>$warnings</td><td>$other</td></tr>"""
            }
        }
        val analysisVerdict = "$analysisErrors analysis error" + if (analysisErrors == 1) "" else "s"
        val verdict = when {
            totalFailures > 0 && analysisErrors > 0 -> "$totalFailures failed, $analysisVerdict"
            totalFailures > 0 -> "$totalFailures failed"
            analysisErrors > 0 -> "tests passed, $analysisVerdict"
            else -> "all passed"
        }
        // Stamped because this report is written on failures too: a run that stopped at static
        // analysis leaves the previous run's XML in place, and without a time there is no way to
        // tell a fresh layer from a stale one.
        val generatedAt = LocalDateTime.now().withNano(0).toString().replace("T", " ")
        summaryDir.mkdirs()
        outFile.writeText(
            """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <title>Jellyshelf test summary</title>
            <style>
              body { font: 15px/1.5 system-ui, sans-serif; margin: 2rem; color: #222; }
              h1 { font-size: 1.3rem; margin: 0 0 .25rem; }
              h2 { font-size: 1rem; margin: 2rem 0 .5rem; }
              .meta { color: #666; font-size: .85rem; margin-bottom: 1.5rem; }
              table { border-collapse: collapse; min-width: 34rem; }
              th, td { padding: .5rem .9rem; border-bottom: 1px solid #e3e3e3; text-align: right; }
              th:first-child, td:first-child { text-align: left; }
              thead th { border-bottom: 2px solid #ccc; font-size: .8rem; text-transform: uppercase; color: #555; }
              tr.fail td:first-child::before { content: "\2717 "; color: #c0392b; }
              tr.pass td:first-child::before { content: "\2713 "; color: #17803d; }
              tr.warn td:first-child::before { content: "\26A0 "; color: #b7791f; }
              tr.notrun td { color: #999; font-style: italic; }
              tfoot td { font-weight: 600; border-top: 2px solid #ccc; border-bottom: none; }
              a { color: #1a4f9c; }
              @media (prefers-color-scheme: dark) {
                body { background: #16181c; color: #e6e6e6; }
                th, td { border-color: #303540; } thead th { color: #9aa4b2; border-color: #454b57; }
                tfoot td { border-color: #454b57; } a { color: #7aa7ff; } .meta { color: #9aa4b2; }
              }
            </style></head><body>
            <h1>Jellyshelf test summary — $verdict</h1>
            <div class="meta">$totalTests tests across ${ran.size} of ${layers.size} layers &middot;
            $analysisFindings static-analysis findings from ${analysed.size} of ${checks.size} tools
            &middot; generated $generatedAt.<br>
            A row reads "not run" when it was skipped (for instance no device attached); one that
            did not re-run in the latest pass still shows its previous results.</div>
            <h2>Tests</h2>
            <table>
              <thead><tr><th>Layer</th><th>Tests</th><th>Passed</th><th>Failed</th><th>Skipped</th></tr></thead>
              <tbody>
$rows
              </tbody>
              <tfoot><tr><td>Total</td><td>$totalTests</td>
              <td>${totalTests - totalFailures - totalSkipped}</td>
              <td>$totalFailures</td><td>$totalSkipped</td></tr></tfoot>
            </table>
            <h2>Static analysis</h2>
            <table>
              <thead><tr><th>Tool</th><th>Findings</th><th>Errors</th><th>Warnings</th><th>Other</th></tr></thead>
              <tbody>
$analysisRows
              </tbody>
              <tfoot><tr><td>Total</td><td>$analysisFindings</td><td>$analysisErrors</td>
              <td>${analysed.sumOf { it.second }}</td><td>${analysed.sumOf { it.third }}</td></tr></tfoot>
            </table>
            </body></html>
            """.trimIndent(),
        )
        // file:// URL rather than a bare path: terminals linkify it, matching how Gradle
        // prints its own report locations.
        logger.lifecycle("Test summary: file://${outFile.absolutePath}")
        parsed.forEach { (label, stats) ->
            val line = stats?.let { "${it.first} tests, ${it.second} failed, ${it.third} skipped" } ?: "not run"
            logger.lifecycle("  %-24s %s".format(label, line))
        }
        checked.forEach { (label, stats) ->
            val line = stats?.let {
                "${it.first + it.second + it.third} findings, ${it.first} errors, ${it.second} warnings"
            } ?: "not run"
            logger.lifecycle("  %-24s %s".format(label, line))
        }
    }
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
    implementation(libs.okhttp)
    implementation(libs.timber)
    implementation(libs.youtubedl.android.library)
    testImplementation(libs.junit)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose behavior tests are instrumented: they need a real Android runtime, and the
    // project deliberately carries no Robolectric. ui-test-manifest supplies the
    // ComponentActivity they launch into.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.koin.bom))
    androidTestImplementation(platform(libs.ktor.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.koin.test)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Renders @PreviewTest previews host-side (LayoutLib) into reference images.
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
    "ksp"(libs.androidx.room.compiler)
}
