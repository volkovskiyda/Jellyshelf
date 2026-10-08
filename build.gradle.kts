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
    // Applied here, not `apply false`: Kotzilla's root application is what lets it see every
    // module's build id and capture Compose navigation across them. Safe because every module
    // that owns screens gets the SDK — there is only :app, and :baselineprofile is a
    // com.android.test module with no Compose of its own. Were a design-system or ui module ever
    // added without the SDK, this would have to become `apply false` plus a per-module apply, and
    // the symptom of getting it wrong is a compile error naming KotzillaScreenHost.
    alias(libs.plugins.kotzilla)
    alias(libs.plugins.detekt)
    // Formatting. The convention plugin (build-logic/) applies and configures ktlint; :app and
    // :baselineprofile name it in their own plugins blocks too, so every project is covered.
    id("jellyshelf.ktlint")
}

// Static analysis for the whole repo, in two tools with no overlap between them. Android lint
// covers the platform-specific checks (see app/build.gradle.kts, checkAllWarnings = true); detekt
// covers Kotlin complexity, naming and style; ktlint (the jellyshelf.ktlint plugin) owns formatting.
//
// detekt used to own formatting too, through the detekt-formatting plugin — which is ktlint's rule
// set wrapped in detekt rules, running whatever ktlint version detekt 1.23.8 happens to embed.
// Running both would report every layout finding twice under two different rule ids, from two
// engines free to disagree, so detekt-formatting was dropped when ktlint arrived. Anything that
// looks like a missing formatting rule now belongs in .editorconfig, not in detekt.yml.
detekt {
    // build-logic/src too: the convention plugins are Kotlin like the rest, and an included build
    // is outside the root's own project set, so nothing else would check them.
    source.from(files("app/src", "baselineprofile/src", "build-logic/src"))
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

// ktlint is applied per project by the jellyshelf.ktlint convention plugin (build-logic/); each of
// the three build scripts names it in its own plugins block, and the plugin carries the explanation
// of why it is per project. The root's aggregate tasks also reach into build-logic, which is an
// included build and so outside `./gradlew ktlintCheck`'s own project set — without this, its
// Kotlin would be the one folder nothing formats. Both names are the ktlint plugin's documented
// lifecycle tasks with no actions of their own, so wiring them with dependsOn is the intended use.
listOf("ktlintCheck", "ktlintFormat").forEach { name ->
    tasks.named(name) { dependsOn(gradle.includedBuild("build-logic").task(":$name")) }
}
