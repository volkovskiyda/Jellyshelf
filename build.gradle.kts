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
}

// Static analysis for the whole repo. Android lint already covers the platform-specific checks
// (see app/build.gradle.kts, checkAllWarnings = true); detekt covers Kotlin style and complexity,
// with detekt-formatting adding the ktlint rule set.
detekt {
    source.from(files("app/src", "baselineprofile/src"))
    config.from(files("config/detekt/detekt.yml"))
    // The config file holds only this project's overrides; everything else comes from detekt's
    // defaults, so a version bump brings new rules instead of freezing a 500-line copy.
    buildUponDefaultConfig = true
    parallel = true
}

dependencies {
    detektPlugins(libs.detekt.formatting)
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
