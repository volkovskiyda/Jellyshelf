package com.gmail.volkovskiyda.jellyshelf.util

/**
 * Stands in for the real [MetricsSink] when `app/kotzilla.json` is absent, and is compiled only
 * then — see the `kotzilla { }` block and the source-set `if` in `app/build.gradle.kts`.
 *
 * The Kotzilla Gradle plugin adds the SDK runtime along with the config it generates, so a checkout
 * with no API keys — a fresh clone, a fork's pull request, CI's build and test jobs — has no
 * `KotzillaCore` class on the classpath at all. Without this object, every call site of [Metrics]
 * would fail to compile there. Same arrangement as `MonitoringFallback.kt` beside it, and for the
 * same reason: the call sites stay unconditional, and nothing has to be remembered when the keys
 * are present.
 *
 * Deliberately references no Kotzilla type, and must stay signature-identical to its twin in
 * `src/kotzillaEnabled` — no build compiles both, so only the two compile runs prove it. CI's
 * keyless jobs prove this shape on every push; any local build with the key file proves the other.
 */
internal object KotzillaSink : MetricsSink {

    override suspend fun <T> suspendTrace(id: String, block: suspend () -> T): T = block()

    override fun mark(label: String, track: String) = Unit

    override fun log(message: String) = Unit
}
