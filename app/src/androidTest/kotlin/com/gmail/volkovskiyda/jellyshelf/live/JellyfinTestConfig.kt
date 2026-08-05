package com.gmail.volkovskiyda.jellyshelf.live

import androidx.test.platform.app.InstrumentationRegistry
import org.koin.dsl.module

/**
 * Live-endpoint test config, read once from the instrumentation runner arguments that Gradle's
 * `loadEnv(".test.env")` feeds into `testInstrumentationRunnerArguments`. The values are never
 * compiled into any BuildConfig — they arrive as runtime `am instrument -e` extras.
 *
 * Credentials are a real username + password — the app's primary sign-in path — not a Jellyfin
 * API key: a key is server-wide and admin-scoped, which is both a needless credential to keep on
 * disk and not what real installs authenticate with. Prefer a dedicated non-admin test user, so
 * live runs never touch a real account's watch state.
 */
data class JellyfinTestConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    /** Sync-scope folder as the in-app picker displays it, e.g. "Home Videos/YouTube". */
    val syncFolder: String,
    /** The same folder's Jellyfin item id, for tests that need it directly (UI tests). */
    val syncFolderId: String,
    private val explicitIndexUrl: String,
) {
    /** Live tests skip (assumeTrue) unless a server URL + username + password are present. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /**
     * `JELLYFIN_INDEX_URL` when set, otherwise the convention the app's own "fill from server"
     * affordance applies: `<server>/jellyshelf-index.json`. Blank only while unconfigured.
     */
    val indexUrl: String
        get() = explicitIndexUrl.ifBlank {
            serverUrl.trim().removeSuffix("/").takeIf { it.isNotBlank() }
                ?.let { "$it/jellyshelf-index.json" }.orEmpty()
        }
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
            username = args.getString("jellyfinUsername").orEmpty(),
            password = args.getString("jellyfinPassword").orEmpty(),
            syncFolder = args.getString("jellyfinSyncFolder").orEmpty(),
            syncFolderId = args.getString("jellyfinSyncFolderId").orEmpty(),
            explicitIndexUrl = args.getString("jellyfinIndexUrl").orEmpty(),
        )
    }
}
