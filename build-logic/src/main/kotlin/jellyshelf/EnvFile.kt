package jellyshelf

import org.gradle.api.Project

/**
 * Reads KEY=VALUE lines from a git-ignored file at the repo root (blanks and `#` comments ignored);
 * a missing file yields an empty map. Each such file has a committed `.example.*` template:
 * `.test.env` carries the opt-in live-endpoint test config, `keystore.properties` the release
 * signing values. The `jellyshelf.android` plugin reads the first for every Android module;
 * `app/build.gradle.kts` reads the second for itself.
 *
 * Read through `providers.fileContents` rather than `File.readLines()`, so the file is a declared
 * input of the configuration rather than an ambient read. The map is still resolved here: every
 * consumer is a plain-valued AGP DSL field (signing passwords, runner arguments), none of which
 * takes a Provider.
 */
fun Project.loadEnv(name: String): Map<String, String> =
    providers.fileContents(layout.settingsDirectory.file(name)).asText
        .map { text ->
            text.lineSequence().mapNotNull { line ->
                line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }
                    ?.split("=", limit = 2)?.takeIf { it.size == 2 }
                    ?.let { (k, v) -> k.trim() to v.trim() }
            }.toMap()
        }
        .getOrElse(emptyMap())
