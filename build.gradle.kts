import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here, not only in :baselineprofile — AGP is already on the build classpath from the
    // line above, so a versioned request in a submodule cannot be version-checked and fails.
    alias(libs.plugins.android.test) apply false
    // Applied by both :app (to consume the profile) and :baselineprofile (to generate it).
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.firebase.perf) apply false
    alias(libs.plugins.detekt)
    // Applied to every project below rather than here, so `apply false`.
    alias(libs.plugins.ktlint) apply false
}

// Static analysis for the whole repo, in two tools with no overlap between them. Android lint
// covers the platform-specific checks (see app/build.gradle.kts, checkAllWarnings = true); detekt
// covers Kotlin complexity, naming and style; ktlint below owns formatting.
//
// detekt used to own formatting too, through the detekt-formatting plugin — which is ktlint's rule
// set wrapped in detekt rules, running whatever ktlint version detekt 1.23.8 happens to embed.
// Running both would report every layout finding twice under two different rule ids, from two
// engines free to disagree, so detekt-formatting was dropped when ktlint arrived. Anything that
// looks like a missing formatting rule now belongs in .editorconfig, not in detekt.yml.
detekt {
    source.from(files("app/src", "baselineprofile/src"))
    config.from(files("config/detekt/detekt.yml"))
    // The config file holds only this project's overrides; everything else comes from detekt's
    // defaults, so a version bump brings new rules instead of freezing a 500-line copy.
    buildUponDefaultConfig = true
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/build/**")
    reports {
        // The HTML report is the one a human opens; the summary task links to it.
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// Formatting, applied to every project rather than pointed at source directories from here the way
// detekt is. The two plugins discover files differently: detekt takes a `source` set of paths,
// while ktlint walks each project's own Kotlin source sets (and, in an Android project, each
// variant's). Applying it only at the root would therefore lint the three build scripts and not one
// line of app code. `allprojects` includes the root, which is how the scripts stay covered.
//
// `./gradlew ktlintCheck` runs the task in every project that has one, so the aggregate command
// stays a single word; `ktlintFormat` is the same set, fixing rather than reporting.
val ktlintVersion = libs.versions.ktlint.get()
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<KtlintExtension> {
        // Pinned from the catalog. Left unset, the plugin picks its own default, which moves with
        // every plugin bump and takes the whole codebase's formatting with it.
        version.set(ktlintVersion)
        // No baseline and no tolerance, matching detekt's `maxIssues: 0`: a finding fails the
        // build. `ktlintFormat` fixes the great majority of them in place.
        ignoreFailures.set(false)
        // Rule ids alongside the message, so a finding says which rule to look up (or to disable in
        // .editorconfig) rather than only what it disliked.
        verbose.set(true)
        reporters {
            // Read in the terminal during a run.
            reporter(ReporterType.PLAIN)
            // Read by :app:testSummary, which parses checkstyle XML for both static-analysis tools.
            reporter(ReporterType.CHECKSTYLE)
            // Read by a human afterwards, and what the summary page links to.
            reporter(ReporterType.HTML)
        }
        filter {
            // Generated Kotlin is on the variant source sets AGP hands the plugin — Room's and
            // Koin's KSP output, and the screenshot plugin's — and none of it is ours to format.
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }
}
