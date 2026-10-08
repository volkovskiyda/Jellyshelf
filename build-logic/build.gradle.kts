plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    // AGP 9.4.1 asks for kotlin-gradle-plugin 2.2.10; the root resolves the catalog's kotlin ref
    // through the Kotlin plugins it applies. Named here so this build sits on the same version.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

// This build cannot apply the jellyshelf.ktlint plugin it compiles, so the one setting that matters
// is pinned here by hand: the engine version, from the same catalog ref. No reporters — nothing
// aggregates them, :app:testSummary reads only the three project builds' reports. The root's
// ktlintCheck and ktlintFormat depend on this build's, which is how one command still covers it.
ktlint {
    version.set(libs.versions.ktlint)
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}
