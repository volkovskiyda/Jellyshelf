import java.time.LocalDateTime

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.compose.screenshot)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
    alias(libs.plugins.androidx.baselineprofile)
}

// Reads KEY=VALUE lines from a repo-root config file (blanks/comments ignored); a missing file
// yields an empty map. Both callers read a git-ignored file that has a committed .example.*
// template: .test.env carries the opt-in live-endpoint test config, keystore.properties the
// release signing values.
fun loadEnv(file: java.io.File): Map<String, String> =
    file.takeIf { it.exists() }?.readLines()
        ?.mapNotNull { line ->
            line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }
                ?.split("=", limit = 2)?.takeIf { it.size == 2 }
                ?.let { (k, v) -> k.trim() to v.trim() }
        }?.toMap().orEmpty()

val testEnv = loadEnv(rootProject.file(".test.env"))
val keystoreEnv = loadEnv(rootProject.file("keystore.properties"))

// Versioning is a CI concern; nothing here is edited per release. Both workflows pass
// -PbuildNumber=$(git rev-list --count HEAD) — one monotonic versionCode shared by App Distribution
// builds and tagged releases, so neither can ever install "over" the other backwards. (github.run_number
// would not do: it counts per workflow.) release.yml additionally passes -PreleaseVersion from the
// tag, which is the only thing that ever sets a versionName by hand. Local and IDE builds pass
// neither and stay at 1 / the base version.
val baseVersion = "1.0"
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull()
val releaseVersion = findProperty("releaseVersion") as String?

android {
    namespace = "com.gmail.volkovskiyda.jellyshelf"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.gmail.volkovskiyda.jellyshelf"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = buildNumber ?: 1
        versionName = releaseVersion ?: buildNumber?.let { "$baseVersion.$it" } ?: baseVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Live-endpoint test config from the git-ignored .test.env, passed as runtime instrumentation
        // extras (never baked into BuildConfig, so changing .test.env needs no rebuild). Blank when
        // the file is absent, so the live tests skip.
        testInstrumentationRunnerArguments += mapOf(
            "jellyfinServerUrl" to testEnv["JELLYFIN_SERVER_URL"].orEmpty(),
            "jellyfinUsername" to testEnv["JELLYFIN_USERNAME"].orEmpty(),
            "jellyfinPassword" to testEnv["JELLYFIN_PASSWORD"].orEmpty(),
            "jellyfinIndexUrl" to testEnv["JELLYFIN_INDEX_URL"].orEmpty(),
            "jellyfinSyncFolder" to testEnv["JELLYFIN_SYNC_FOLDER"].orEmpty(),
            "jellyfinSyncFolderId" to testEnv["JELLYFIN_SYNC_FOLDER_ID"].orEmpty(),
            "jellyfinTestItemId" to testEnv["JELLYFIN_TEST_ITEM_ID"].orEmpty(),
        )

        // youtubedl-android bundles a Python runtime per ABI. Ship arm64 only — it covers
        // virtually all modern physical devices and keeps the APK from ballooning across ABIs.
        ndk {
            //noinspection ChromeOsAbiSupport -- arm64 only by design; x86_64 would bundle a second Python runtime.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // A project-local debug keystore, committed with the standard debug credentials, instead of
        // the per-machine ~/.android/debug.keystore AGP would otherwise generate. It gives this
        // laptop, any contributor and CI the same debug SHA-1, which is what the Firebase API key's
        // Android app restriction is pinned to (see app/google-services.json). The shared keystore
        // is deliberately left where it is — other projects' installed debug builds depend on it.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Only created when keystore.properties supplies a keystore. Without it assembleRelease
        // still configures and builds, producing an unsigned APK — PR CI never needs signing.
        if (keystoreEnv.containsKey("KEYSTORE_FILE")) {
            create("release") {
                storeFile = rootProject.file(keystoreEnv.getValue("KEYSTORE_FILE"))
                storePassword = keystoreEnv.getValue("KEYSTORE_PASSWORD")
                keyAlias = keystoreEnv.getValue("KEY_ALIAS")
                // PKCS12 (keytool's default store type): the key password IS the store password.
                keyPassword = keystoreEnv.getValue("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Install debug and release side by side, and change the launcher icon
            // (debug overrides ic_launcher_foreground in src/debug/res with a "d" badge).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug isn't minified, so there is no mapping worth uploading — the task would just
            // cost build time and demand credentials on every assembleDebug, including CI's.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
            // Debug never reports perf data (JellyshelfApplication gates collection to release),
            // so the perf plugin's bytecode weaving would only slow every debug build.
            configure<com.google.firebase.perf.plugin.FirebasePerfExtension> {
                setInstrumentationEnabled(false)
            }
        }
        release {
            // findByName, not getByName: null on a checkout without keystore.properties, which
            // leaves the APK unsigned rather than failing configuration. minSdk 31 means AGP
            // signs with v2+ automatically, so no per-scheme flags are needed.
            signingConfig = signingConfigs.findByName("release")
            // R8 shrinking/obfuscation. Library consumer rules (Ktor, Room, kotlinx-serialization)
            // come in automatically; app-specific rules live in src/main/keepRules/.
            optimization {
                enable = true
            }
            // Ship the R8 mapping to Crashlytics so release stack traces arrive deobfuscated.
            // Item 05 attaches the same file to each GitHub Release; the two serve different
            // readers and both are worth having.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = true
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
        // The one known third-party false positive (ktor-utils referencing java.lang.management)
        // is suppressed per-artifact in lint.xml, not baselined: a baseline pins the finding to a
        // jar path that carries the version, so it needed regenerating on every ktor bump.
        // Also print findings to the console; file reports in build/reports/ stay as-is.
        printTextReport = true
    }
}

ksp {
    // Check generated Room schemas into app/schemas so version bumps can ship real migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The baseline-profile plugin derives its nonMinifiedRelease variant from release so the generator
// runs against readable class names, and it does that by clearing the legacy isMinifyEnabled flag —
// which this project never sets: R8 is switched on through the release buildType's optimization
// block, and it survived the copy. The first profile generated here came out fully obfuscated as a
// result, which is worse than having none, because R8 picks fresh names on every build and not one
// entry would have matched the APK it shipped in.
//
// finalizeDsl rather than the buildTypes block: the plugin copies release's settings onto the new
// build type after any configureEach there has run, so this has to be the last word. benchmarkRelease
// is deliberately left minified — that variant exists to be release-like.
androidComponents {
    finalizeDsl { android ->
        // Neither profiling variant may share the shipped app's application id. They did once, and
        // a generation run then installed *over* the release build on the device: it signed that
        // install in as the .test.env user, and AGP's connected-test teardown uninstalled it
        // afterwards, credentials and synced library with it. `.benchmark` makes them a package of
        // their own, sitting beside both the release build and `.debug`. The id costs a client
        // entry in app/google-services.json — the google-services plugin fails the build with
        // "No matching client found for package name" without one — and it cannot reach the
        // profile, which is a list of classes and methods in a namespace that does not change.
        // BaselineProfileGenerator therefore reads the id from the `targetAppId` runner argument
        // rather than hardcoding it; :baselineprofile's build script fills that in.
        android.buildTypes
            .filter { it.name.startsWith("nonMinified") || it.name.startsWith("benchmark") }
            .forEach { buildType ->
                buildType.applicationIdSuffix = ".benchmark"
                buildType.versionNameSuffix = "-benchmark"
                // A badged launcher icon and name, the way debug has one: three installs of the
                // same app are otherwise three identical icons, and the one you must not tap is
                // the one that gets wiped and re-signed-in on every run. src/benchmark/res is a
                // shared directory rather than a source set of its own — the build types these
                // variants come from are created by the baseline-profile plugin, so neither
                // src/nonMinifiedRelease nor src/benchmarkRelease would hold it without being
                // written out twice.
                android.sourceSets.getByName(buildType.name).res.directories += "src/benchmark/res"
            }
        android.buildTypes.filter { it.name.startsWith("nonMinified") }.forEach { buildType ->
            buildType.optimization.enable = false
            // Signing is left exactly as inherited from release — the generator should profile a
            // build signed the way the shipped one is — and only filled in when that inheritance
            // yields nothing. It yields nothing on a checkout without keystore.properties, where
            // release's config is never created (see buildTypes.release above): the APK then comes
            // out unsigned, the device refuses to install it, and generating a profile would be
            // impossible for anyone but the keystore holder. The committed debug keystore is the
            // fallback for that case alone. Signing cannot reach the profile either way — it is a
            // list of classes and methods.
            if (buildType.signingConfig == null) {
                buildType.signingConfig = android.signingConfigs.getByName("debug")
            }
        }
    }
}

baselineProfile {
    // A release build must never need a device. Generation is the deliberate manual step described
    // in :baselineprofile's BaselineProfileGenerator — run against the physical Pixel 5, with its
    // output committed — because the arm64-only APK installs on nothing CI can offer. Leaving this
    // at its default true would make assembleRelease try to generate, and CI would fail.
    automaticGenerationDuringBuild = false
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

// Single HTML page summarising every test layer and all three static-analysis tools, written to
// `app/build/test-summary/index.html`. Only reads XML that is already on disk — it never runs a
// test or a check itself, so it is safe to attach to any pipeline; `scripts/run-tests.sh` calls it
// once the layers it ran have finished.
//
// Everything lives inside doLast: the configuration cache cannot serialize references to
// build-script-level functions or classes, so the parsers are local lambdas rather than helpers.
//
// A line comment rather than KDoc: nothing reads a build script's KDoc, and `tasks.register(...)`
// is a call rather than a declaration for one to attach to — which is what ktlint's `standard:kdoc`
// rule objects to.
tasks.register("testSummary") {
    group = "verification"
    description =
        "Aggregate test results (unit, screenshot, instrumented) and static analysis " +
        "(detekt, ktlint, lint) into one HTML report."
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
    // Static analysis: findings rather than tests, so these get their own table. All three tools
    // write an XML report next to the HTML one a human opens; the second element of each entry is
    // where to look for that XML — a file, or a directory to walk — and the third is the HTML to
    // link to, or null when there is no single one to name (see ktlint below).
    val checks = listOf(
        Triple(
            "detekt",
            listOf(rootBuildDir.resolve("reports/detekt/detekt.xml")),
            rootBuildDir.resolve("reports/detekt/detekt.html"),
        ),
        // Directories rather than files, and three of them: ktlint runs per project and per source
        // set, so :app alone writes one report for main, one for test, one for androidTest, one for
        // screenshotTest and one for its build script, with the root project and :baselineprofile
        // adding theirs. Summing them is the only way to get one number, and there is no single
        // HTML page to link to — the link is resolved below, to whichever report has findings.
        Triple(
            "ktlint",
            listOf(
                buildDir.resolve("reports/ktlint"),
                rootBuildDir.resolve("reports/ktlint"),
                rootProject.file("baselineprofile/build/reports/ktlint"),
            ),
            null,
        ),
        // lintDebug only, matching scripts/run-tests.sh — the release variant reports the same
        // findings a second time.
        Triple(
            "Android lint (debug)",
            listOf(buildDir.resolve("reports/lint-results-debug.xml")),
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

        // Splits the behavior layer by device, for the case the single row cannot answer: which
        // device failed, and on what. Only consulted when something failed — a green run says so
        // in one line and does not need the breakdown.
        //
        // The device name comes from the XML itself (each <testsuite> carries
        // <property name="device" …/>), not from the file name, so it survives the renaming
        // scripts/run-tests.sh does when it stashes one pass out of the next pass's way. The phase
        // is inferred the same way — a file whose every case is in the live package is the live
        // pass — rather than from that name, for the same reason.
        val perDevice = { dir: File ->
            dir.walkTopDown().filter { it.isFile && it.extension == "xml" }.mapNotNull { file ->
                runCatching {
                    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(file)
                    val elements = { tag: String ->
                        val nodes = doc.getElementsByTagName(tag)
                        (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                    }
                    val device = elements("property")
                        .firstOrNull { it.getAttribute("name") == "device" }
                        ?.getAttribute("value")
                        ?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension
                    val cases = elements("testcase")
                    val live = cases.count { it.getAttribute("classname").contains(".live.") }
                    val phase = when {
                        cases.isEmpty() || live == 0 -> "offline"
                        live == cases.size -> "live"
                        // One invocation carrying both: a single-device run, which is not split.
                        else -> ""
                    }
                    val failed = cases.filter { case ->
                        val children = case.childNodes
                        (0 until children.length).any {
                            val name = children.item(it).nodeName
                            name == "failure" || name == "error"
                        }
                    }.map { "${it.getAttribute("classname")}.${it.getAttribute("name")}" }
                    // Totals from the <testsuites> wrapper when present — it is the file's own
                    // header — falling back to counting cases for a file that lacks one.
                    val header = elements("testsuites").firstOrNull()
                    val attr = { name: String -> header?.getAttribute(name)?.toIntOrNull() ?: 0 }
                    val tests = if (header != null) attr("tests") else cases.size
                    val skipped = if (header != null) attr("skipped") else 0
                    Triple(device to phase, Triple(tests, failed.size.coerceAtLeast(attr("failures")), skipped), failed)
                }.getOrNull()
            }.sortedWith(compareBy({ it.first.first }, { it.first.second })).toList()
        }

        // Every XML report a check entry points at: the file itself, or every `*.xml` under it when
        // the entry names a directory. Empty when the tool never ran, which is what lets the row
        // below say "not run" rather than "0 findings".
        //
        // ktlint's `*Format` reports are excluded deliberately. Those list what `ktlintFormat`
        // *fixed*, not what is still wrong, and counting them would report a clean tree as dirty
        // for as long as the last format run's output sat in the build directory.
        val findingReports = { roots: List<File> ->
            roots.flatMap { root ->
                when {
                    root.isFile -> listOf(root)
                    root.isDirectory ->
                        root.walkTopDown()
                            .filter { it.isFile && it.extension == "xml" && !it.parentFile.name.endsWith("Format") }
                            .toList()
                    else -> emptyList()
                }
            }
        }

        // Counts findings by severity across a tool's checkstyle (`<error>`) or lint (`<issue>`)
        // reports; null when there are none to read, i.e. the tool never ran. A real XML parse here
        // rather than the line scan above: lint embeds multi-line rule explanations in its
        // attributes, which regexes read wrong. Unreadable XML counts as "not run" instead of
        // failing the summary.
        val parseFindings = { xmls: List<File> ->
            xmls.takeIf { it.isNotEmpty() }?.let { files ->
                runCatching {
                    val severities = files.flatMap { xml ->
                        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(xml)
                        listOf("error", "issue").flatMap { tag ->
                            val nodes = doc.getElementsByTagName(tag)
                            (0 until nodes.length).map { i ->
                                (nodes.item(i) as org.w3c.dom.Element).getAttribute("severity").lowercase()
                            }
                        }
                    }
                    // detekt and ktlint emit error/warning/info, lint fatal/error/warning/
                    // information/hint; everything below a warning is folded into "other" (lint's
                    // baseline note lands there, for one).
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
        val checked = checks.map { (label, roots, report) ->
            val xmls = findingReports(roots)
            // A tool with one report links to it. A tool with many (ktlint) links to the first that
            // has an `<error>` in it, and to none when the whole run was clean — where the row's
            // own "0 findings" is the entire story and a link to an empty page is noise.
            val link = report?.let(href)
                ?: xmls.firstOrNull { it.readText().contains("<error") }
                    ?.let { File(it.parentFile, "${it.nameWithoutExtension}.html") }
                    ?.takeIf { it.exists() }
                    ?.let(href)
            Triple(label, parseFindings(xmls), link)
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
        val behaviorLabel = "Behavior tests (device)"
        val behaviorDir = layers.first { it.first == behaviorLabel }.second
        val behaviorFailed = (parsed.firstOrNull { it.first == behaviorLabel }?.second?.second ?: 0) > 0
        // Only when it failed, and only when more than one file contributed: one device running one
        // invocation is already fully described by the row above it.
        val breakdown = if (behaviorFailed) perDevice(behaviorDir).takeIf { it.size > 1 }.orEmpty() else emptyList()

        val rows = parsed.joinToString("\n") { (label, stats, href) ->
            val row = if (stats == null) {
                """      <tr class="notrun"><td>$label</td><td colspan="4">not run</td></tr>"""
            } else {
                val (tests, failures, skipped) = stats
                val name = href?.let { """<a href="$it">$label</a>""" } ?: label
                val cls = if (failures > 0) "fail" else "pass"
                """      <tr class="$cls"><td>$name</td><td>$tests</td>""" +
                    """<td>${tests - failures - skipped}</td><td>$failures</td><td>$skipped</td></tr>"""
            }
            if (label != behaviorLabel || breakdown.isEmpty()) {
                row
            } else {
                val sub = breakdown.joinToString("\n") { (key, stats, _) ->
                    val (device, phase) = key
                    val (tests, failures, skipped) = stats
                    val suffix = if (phase.isEmpty()) "" else " — $phase"
                    """      <tr class="detail ${if (failures > 0) "fail" else "pass"}">""" +
                        """<td>$device$suffix</td><td>$tests</td>""" +
                        """<td>${tests - failures - skipped}</td><td>$failures</td><td>$skipped</td></tr>"""
                }
                "$row\n$sub"
            }
        }

        // The list the terminal cannot hold: every failed case, named, under the device that failed
        // it. Capped per group, because one broken emulator produces hundreds of identical lines
        // and the point is to identify the device, not to reprint the suite.
        val failureLimit = 20
        val failureSections = breakdown.filter { it.third.isNotEmpty() }.joinToString("\n") { (key, _, failed) ->
            val (device, phase) = key
            val shown = failed.take(failureLimit).joinToString("\n") { "              <li>$it</li>" }
            val more = (failed.size - failureLimit).takeIf { it > 0 }
                ?.let { """              <li class="more">…and $it more</li>""" }.orEmpty()
            val suffix = if (phase.isEmpty()) "" else " — $phase"
            """            <h3>$device$suffix &middot; ${failed.size} failed</h3>
            <ul class="failures">
$shown
$more
            </ul>"""
        }
        val failuresHtml = if (failureSections.isBlank()) {
            ""
        } else {
            "            <h2>What failed, by device</h2>\n$failureSections"
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
        // Failed goldens are usually an intentional UI change, not a bug — surface the re-bake
        // command on the page itself; scripts/run-tests.sh prints the same hint in the terminal.
        val screenshotFailed = parsed.any { (label, stats, _) ->
            label == "Screenshot goldens" && (stats?.second ?: 0) > 0
        }
        val screenshotHint = if (screenshotFailed) {
            """<p class="hint">Screenshot goldens differ. If the change is intentional """ +
                """(a new feature, a fixed typo), re-bake the baselines and re-run:<br>""" +
                """<code>./gradlew :app:updateDebugScreenshotTest</code></p>"""
        } else {
            ""
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
              tr.detail td { color: #555; font-size: .9rem; }
              tr.detail td:first-child { padding-left: 2.2rem; }
              h3 { font-size: .95rem; margin: 1.2rem 0 .3rem; }
              ul.failures { margin: 0; padding-left: 1.4rem; font-size: .9rem; color: #555; }
              ul.failures li { font-family: ui-monospace, monospace; font-size: .82rem; }
              ul.failures li.more { font-family: inherit; font-style: italic; }
              tfoot td { font-weight: 600; border-top: 2px solid #ccc; border-bottom: none; }
              a { color: #1a4f9c; }
              .hint { margin: .75rem 0 0; color: #b7791f; }
              .hint code { background: #f2f2f2; padding: .1rem .4rem; border-radius: 4px; color: #222; }
              @media (prefers-color-scheme: dark) {
                body { background: #16181c; color: #e6e6e6; }
                tr.detail td, ul.failures { color: #9aa4b2; }
                th, td { border-color: #303540; } thead th { color: #9aa4b2; border-color: #454b57; }
                tfoot td { border-color: #454b57; } a { color: #7aa7ff; } .meta { color: #9aa4b2; }
                .hint code { background: #232833; color: #e6e6e6; }
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
$screenshotHint
$failuresHtml
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
            // Printed here rather than left to the HTML: "behavior tests FAILED" on a multi-device
            // run does not say which device, and that is the first thing anyone asks.
            if (label == behaviorLabel) {
                breakdown.forEach { (key, stats, failed) ->
                    val (device, phase) = key
                    val suffix = if (phase.isEmpty()) "" else " ($phase)"
                    logger.lifecycle(
                        "    %-30s %s".format(
                            device + suffix,
                            "${stats.first} tests, ${stats.second} failed",
                        ),
                    )
                    failed.take(5).forEach { logger.lifecycle("      - $it") }
                    (failed.size - 5).takeIf { it > 0 }?.let { logger.lifecycle("      …and $it more") }
                }
            }
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
    // No app code names an okhttp type — OkHttp arrives only as Ktor's engine and as a
    // firebase-perf transitive. A constraint rather than an `implementation` is the honest way to
    // say that: it raises the version wherever the module already appears, and adds nothing to the
    // graph if it stops appearing. Both requesters ask for something older (measured on
    // releaseRuntimeClasspath: Ktor 3.5.2 asks 5.3.2, firebase-perf 22.0.6 asks 4.12.0), so without
    // this every HTTP call in the app quietly drops to 5.3.2.
    constraints {
        implementation(libs.okhttp)
    }

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
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Installs the committed baseline profile at runtime on devices where ART does not pick it up
    // from the APK by itself. Without it the profile ships but may never be applied.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    // System-trace sections (util/Traces.kt). Already on the classpath transitively — profileinstaller
    // and Compose both pull it — but this app writes its own sections, so it declares the version it
    // compiles against rather than inheriting whichever one another library happens to want.
    implementation(libs.androidx.tracing)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    // Coil 3 registers no network fetcher on its own; di/AppModule.kt wires this one to a
    // dedicated Ktor client, so images ride the same HTTP stack as the rest of the app.
    implementation(libs.coil.network.ktor3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    // App Distribution ships as two artifacts, and the split is Firebase's own recommendation.
    // The -api one is a no-op stub: every call returns a Task that fails with NOT_IMPLEMENTED and
    // isTesterSignedIn() is a hardcoded false, so a build carrying only it compiles and runs but
    // finds nothing. That is the right outcome off the release build type — CI uploads only to the
    // release Firebase app id, so the .debug and .benchmark apps have no releases to find.
    //
    // releaseImplementation DOES reach nonMinifiedRelease and benchmarkRelease — measured from
    // their runtime classpaths on 2026-08-06, not assumed. The baseline-profile plugin derives
    // those build types from release (see the block above) and they inherit its dependencies with
    // it, so the profiling variants link the full SDK and gain the permissions below too. Nothing
    // stops them checking for updates except the update-source preference defaulting to NONE:
    // they report isDebug = false and carry a real versionCode, so no build-type gate catches
    // them. Those APKs install as .benchmark and are never shipped.
    //
    // The full SDK merges REQUEST_INSTALL_PACKAGES, POST_NOTIFICATIONS, READ_/WRITE_EXTERNAL_STORAGE
    // and an exported SignInResultActivity into every release APK, opted in or not — an accepted
    // trade, since the stub cannot detect releases at all. docs/RELEASING.md carries the detail.
    implementation(libs.firebase.appdistribution.api)
    releaseImplementation(libs.firebase.appdistribution)
    // Both are for TesterSignInLauncher, which opens the tester sign-in itself rather than letting
    // the SDK do it — the SDK hardcodes FLAG_ACTIVITY_NEW_TASK, which puts the Custom Tab in the
    // browser's own task where nothing in this app can reach or close it.
    //
    // Declared on every variant, not releaseImplementation: both arrive transitively through the
    // full App Distribution SDK, which debug does not link, so the launcher would not compile.
    // firebase-installations supplies the installation id the sign-in URL is keyed to.
    implementation(libs.androidx.browser)
    implementation(libs.firebase.installations)
    // The kill switch for that launcher (UpdateFlags). Already present transitively — Performance
    // configures itself through Remote Config — but a switch this app depends on being able to
    // read should not rest on another SDK continuing to want it.
    implementation(libs.firebase.config)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
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
    // Compose behavior tests are instrumented: they need a real Android runtime, and the
    // project deliberately carries no Robolectric. ui-test-manifest supplies the
    // ComponentActivity they launch into.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Fails a Compose test on unlabelled clickables, undersized touch targets and low contrast,
    // checked before every action that changes the UI. BOM-managed; pulls the Accessibility Test
    // Framework transitively. @RequiresApi(34) — both run surfaces (the Pixel 5 on API 34, Test
    // Lab on API 36) clear it, and lint does not check test sources, so minSdk 31 is not a bar.
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.koin.bom))
    androidTestImplementation(platform(libs.ktor.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Not used directly — Compose's test rules drive Espresso underneath. Declared purely to pull
    // the resolved version off 3.5.0, whose input injection is broken on Android 16. See the
    // androidxTestEspresso comment in libs.versions.toml.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.koin.test)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Renders @PreviewTest previews host-side (LayoutLib) into reference images.
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
    // Where generateBaselineProfile takes its profile from; consumed, not built, by normal builds.
    baselineProfile(project(":baselineprofile"))
    "ksp"(libs.androidx.room.compiler)
}
