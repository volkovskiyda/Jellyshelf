import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Formatting, applied to every project rather than pointed at source directories from the root
// the way detekt is. The two plugins discover files differently: detekt takes a `source` set of
// paths, while ktlint walks each project's own Kotlin source sets (and, in an Android project,
// each variant's). Applying it only at the root would therefore lint the three build scripts and
// not one line of app code. Each of the root, :app and :baselineprofile names this plugin in its
// own plugins block, which is how the scripts stay covered alongside the sources.
//
// `./gradlew ktlintCheck` runs the task in every project that has one, so the aggregate command
// stays a single word; `ktlintFormat` is the same set, fixing rather than reporting.
plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

// The catalog by name rather than through the generated `libs` accessor, which precompiled script
// plugins do not get. `findVersion(...).get()` is java.util.Optional on a constant, not a Provider.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

ktlint {
    // Pinned from the catalog. Left unset, the plugin picks its own default, which moves with
    // every plugin bump and takes the whole codebase's formatting with it.
    version.set(libs.findVersion("ktlint").get().requiredVersion)
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
