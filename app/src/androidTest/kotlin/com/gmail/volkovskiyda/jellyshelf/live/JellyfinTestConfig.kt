package com.gmail.volkovskiyda.jellyshelf.live

import androidx.test.platform.app.InstrumentationRegistry
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.grantJourneyPermissions
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
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
    /**
     * The one item [LiveUiJourneyTest] is allowed to write to, or blank to fall back to the
     * library's first video. Pin a disposable item here when the first video is not one whose
     * watch state may be toggled — that is the only choice the journey offers, and by design:
     * nothing else in the library is ever written to.
     */
    val testItemId: String,
    /**
     * Base URL of the metadata API on the bot server, blank when not configured — and blank is the
     * normal state: only [LiveMetadataApiTest] reads it, and every other live test runs without it
     * on purpose, so a plain live run proves the app syncs with no metadata API at all.
     */
    val metadataApiUrl: String,
    /** The bearer token that API requires. Useless without [metadataApiUrl] and vice versa. */
    val metadataApiToken: String,
    private val explicitIndexUrl: String,
) {
    /** Live tests skip (assumeTrue) unless a server URL + username + password are present. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /**
     * Whether sync has somewhere to be scoped to — [syncFolder], [syncFolderId], or both, which
     * must then name the same folder. Either one alone is enough, and they buy different things:
     * the path is what the in-app picker is walked by (so it covers the picker), the id is what the
     * app stores, and only the id can be checked against what the picker actually chose.
     *
     * [LiveUiJourneyTest] requires this rather than defaulting to the root: an unscoped sync pulls
     * a whole server through the app, which is not a thing a test may start.
     */
    val hasSyncScope: Boolean get() = syncFolder.isNotBlank() || syncFolderId.isNotBlank()

    /**
     * Whether the metadata API is configured — **both** halves, since a URL without its token can
     * only produce 401s and a token without a URL is never sent anywhere. Half-filled counts as
     * unconfigured here, and [LiveMetadataApiTest] fails rather than skips on it: a run that
     * silently skipped would look exactly like the deliberate blank state.
     */
    val hasMetadataApi: Boolean get() = metadataApiUrl.isNotBlank() && metadataApiToken.isNotBlank()

    /** True when exactly one half of the metadata API pair is filled in — a misconfiguration. */
    val metadataApiHalfConfigured: Boolean
        get() = metadataApiUrl.isNotBlank() != metadataApiToken.isNotBlank()

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
            testItemId = args.getString("jellyfinTestItemId").orEmpty(),
            metadataApiUrl = args.getString("jellyfinMetadataApiUrl").orEmpty(),
            metadataApiToken = args.getString("jellyfinMetadataApiToken").orEmpty(),
            explicitIndexUrl = args.getString("jellyfinIndexUrl").orEmpty(),
        )
    }
}

/**
 * A [JellyfinClient] that reaches Jellyfin as a device of its own, named by [deviceId].
 *
 * Live tests cannot share the app's `JellyfinClient` from `appModule`, because that one reads the
 * install's persisted device id — the app's. Jellyfin keys a session on the device, and issuing a
 * second token for one invalidates the first, so a test holding an app-device token loses it the
 * moment the journey signs the app in and every later call 401s. A distinct id per test class also
 * keeps the dashboard showing one stable device per suite rather than one per run.
 *
 * Everything else is the real thing: [baseClient] is `appModule`'s tuned Ktor client and
 * [deviceInfo] the app's own, so the stack under test is the shipped one. The settings fake is
 * there for [SettingsRepository.deviceId] and nothing else — that is all a client reads from it.
 */
internal fun liveJellyfinClient(
    baseClient: HttpClient,
    deviceInfo: DeviceInfo,
    deviceId: String,
): JellyfinClient = JellyfinClient(baseClient, FakeSettingsRepository(deviceId = deviceId), deviceInfo)

/**
 * Fast reachability probe with a short timeout, so a down server skips the live tests quickly
 * instead of hanging on each of them. `/System/Info/Public` needs no credentials.
 */
internal fun serverReachable(serverUrl: String): Boolean =
    reachable(serverUrl, "/System/Info/Public") { it.isSuccess() }

/**
 * The same probe for the metadata API host, which [LiveMetadataApiTest] needs separately: it is a
 * different server from Jellyfin, on a name of its own, so a reachable Jellyfin says nothing about
 * whether the bot server can be reached. The two routinely differ — a Jellyfin published to a
 * public name resolves from anywhere, while a LAN-only metadata API resolves only on the subnet,
 * which is why an emulator (public DNS) fails to find a host the developer's machine finds.
 *
 * **Any HTTP answer counts as reachable, 401 included.** The suite's whole subject is what the
 * server answers — that the token is enforced, that the documents carry what the merge needs — so
 * anything short of a transport failure must reach the assertions rather than skip past them.
 * The probe deliberately sends no token: it asks whether the host is there, not whether auth works.
 */
internal fun metadataApiReachable(apiUrl: String): Boolean =
    reachable(apiUrl, "/videos") { true }

/**
 * It grants the journey permissions first, because the probe is subject to the same
 * `ACCESS_LOCAL_NETWORK` rule as the app: the instrumented process runs under the app's uid, so
 * on API 37 a server on the device's own subnet is dropped for this probe exactly as it would be
 * for a sign-in. Without the grant every live test skipped on such a device as "unreachable" —
 * a green run that covered nothing, and one no `@Before` could rescue, since each of them is
 * behind this assumption. Here rather than in each test so the guard and its precondition cannot
 * drift apart.
 */
private fun reachable(
    baseUrl: String,
    path: String,
    accept: (HttpStatusCode) -> Boolean,
): Boolean = runCatching {
    grantJourneyPermissions()
    runBlocking {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = PROBE_TIMEOUT_MS
                connectTimeoutMillis = PROBE_TIMEOUT_MS
                socketTimeoutMillis = PROBE_TIMEOUT_MS
            }
        }.use { probe ->
            accept(probe.get("${baseUrl.trim().removeSuffix("/")}$path").status)
        }
    }
}.getOrDefault(false)

private const val PROBE_TIMEOUT_MS = 3_000L
