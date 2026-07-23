package com.gmail.volkovskiyda.jellyshelf.live

import androidx.test.platform.app.InstrumentationRegistry
import org.koin.dsl.module

/**
 * Live-endpoint test config, read once from the instrumentation runner arguments that Gradle's
 * `loadEnv(".test.env")` feeds into `testInstrumentationRunnerArguments`. The values are never
 * compiled into any BuildConfig — they arrive as runtime `am instrument -e` extras.
 */
data class JellyfinTestConfig(
    val serverUrl: String,
    val apiKey: String,
    val indexUrl: String,
) {
    /** Live tests skip (assumeTrue) unless a server URL + API key are present. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

/**
 * Koin module that provides [JellyfinTestConfig] to the live tests. Reading the runner arguments
 * here — once, in the module — keeps one source of truth and keeps `InstrumentationRegistry` out of
 * every test. Live tests `loadKoinModules(liveTestModule)` and `by inject` this alongside the real
 * `JellyfinClient` from `appModule`, so they exercise the migrated Ktor stack end-to-end.
 */
val liveTestModule = module {
    single {
        val args = InstrumentationRegistry.getArguments()
        JellyfinTestConfig(
            serverUrl = args.getString("jellyfinServerUrl").orEmpty(),
            apiKey = args.getString("jellyfinApiKey").orEmpty(),
            indexUrl = args.getString("jellyfinIndexUrl").orEmpty(),
        )
    }
}
