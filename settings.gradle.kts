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
        // Same filter as the plugin repositories above: Google's Maven serves only these groups, so
        // every other coordinate goes straight to Maven Central instead of being looked up on both.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Jellyshelf"
include(":app")
// Macrobenchmark module whose only job is generating the committed baseline profile. It builds
// nothing that ships and CI never invokes it (see app/build.gradle.kts, baselineProfile { }).
include(":baselineprofile")
