package jellyshelf

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Reads KEY=VALUE lines from a git-ignored file at the repo root (blanks and `#` comments ignored);
 * a missing file yields an empty map. Each such file has a committed `.example.*` template:
 * `.test.env` carries the opt-in live-endpoint test config, `keystore.properties` the release
 * signing values. The `jellyshelf.android` plugin reads the first for every Android module, lazily;
 * `app/build.gradle.kts` reads the second for itself, through [loadEnv].
 *
 * Read through `providers.fileContents` rather than `File.readLines()`, so the file is a declared
 * input rather than an ambient read. This is the lazy form: nothing is read until a consumer asks.
 * `jellyshelf.android` hands it straight to the variant API's runner-argument map, which takes a
 * Provider, so a build that never runs a connected test never reads `.test.env` at all.
 */
fun Project.envFile(name: String): Provider<Map<String, String>> =
    providers.fileContents(layout.settingsDirectory.file(name)).asText
        .map { text ->
            text.lineSequence().mapNotNull { line ->
                line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }
                    ?.split("=", limit = 2)?.takeIf { it.size == 2 }
                    ?.let { (k, v) -> k.trim() to v.trim() }
            }.toMap()
        }
        .orElse(emptyMap())

/**
 * [envFile], resolved now — for the one consumer with no lazy route: release signing. Whether the
 * release signing config exists at all is a configuration-time decision, and AGP's signing DSL
 * (store file, passwords, alias) takes plain values; the variant API's `SigningConfig` offers only
 * `setConfig` with another DSL object, so there is nothing to hand a Provider to.
 */
fun Project.loadEnv(name: String): Map<String, String> = envFile(name).get()
