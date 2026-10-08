// The build that compiles the jellyshelf.* convention plugins (src/main/kotlin). It is an included
// build, so it has a settings file of its own and cannot read the root's: the repositories below
// repeat the root's settings.gradle.kts, filter for filter, on purpose.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Plugins are ordinary dependencies of this build, and ktlint-gradle is published on the
        // Plugin Portal only (its 14.2.0 POM is not on Central — checked 2026-10-08). Last, so it
        // is asked only for what the two above do not have.
        gradlePluginPortal()
    }
    versionCatalogs {
        // The root's catalog, so AGP, KGP and ktlint-gradle are pinned here to the same refs the
        // app builds with — one place to bump, as everywhere else.
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
