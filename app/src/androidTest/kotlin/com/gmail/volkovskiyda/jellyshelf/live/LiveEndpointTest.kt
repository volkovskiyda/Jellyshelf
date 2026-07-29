package com.gmail.volkovskiyda.jellyshelf.live

import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * End-to-end read-only checks against a real self-hosted Jellyfin, proving the migrated endpoints
 * deserialize live. Opt-in and tolerant of a down server: each test skips (never fails) when
 * `.test.env` is absent/blank ([JellyfinTestConfig.isConfigured]) or the server is unreachable.
 *
 * Uses the real [JellyfinClient]/[JellyfinDataSource] from `appModule` (tuned base Ktor client +
 * ContentNegotiation + kotlinx `Json` + timeouts), so it exercises the actual migrated stack.
 *
 * Write endpoints (markPlayed/updateUserData/createPlaylist) are intentionally not driven here —
 * they mutate real watch state, and a safe run needs a designated disposable test item on the
 * configured server (a follow-up, see the plan's item 09).
 */
class LiveEndpointTest : KoinTest {

    private val config by inject<JellyfinTestConfig>()
    private val jellyfinClient by inject<JellyfinClient>()
    private val dataSource by inject<JellyfinDataSource>()

    @Before
    fun setUp() {
        loadKoinModules(liveTestModule)
        assumeTrue("no .test.env config — skipping live-endpoint test", config.isConfigured)
        assumeTrue("Jellyfin server unreachable — skipping live-endpoint test", reachable(config.serverUrl))
    }

    @After
    fun tearDown() = unloadKoinModules(liveTestModule)

    @Test
    fun getUsers_returnsAtLeastOneUser() = runTest {
        val api = jellyfinClient.create(config.serverUrl, config.apiKey)
        assertTrue(api.getUsers().isNotEmpty())
    }

    @Test
    fun getViews_forFirstUser_deserializes() = runTest {
        val api = jellyfinClient.create(config.serverUrl, config.apiKey)
        val userId = api.getUsers().first().id
        // Round-tripping without throwing is the assertion: proves ItemsResponse/BaseItemDto decode.
        val views = api.getViews(userId)
        assertTrue(views.items.size >= 0)
    }

    @Test
    fun getItems_forFirstUser_deserializes() = runTest {
        val api = jellyfinClient.create(config.serverUrl, config.apiKey)
        val userId = api.getUsers().first().id
        val resp = api.getItems(userId = userId, limit = 5)
        assertTrue(resp.items.size >= 0)
    }

    @Test
    fun getChildFolders_topLevelViews_deserialize() = runTest {
        val userId = jellyfinClient.create(config.serverUrl, config.apiKey).getUsers().first().id
        // A blank parentId returns the user's top-level collections (views); the point is that the
        // paged BaseItemDto list deserializes without throwing.
        val folders = dataSource.getChildFolders(config.serverUrl, config.apiKey, userId, parentId = null)
        assertTrue(folders.size >= 0)
    }

    @Test
    fun fetchIndex_whenIndexUrlConfigured_deserializes() = runTest {
        assumeTrue("no JELLYFIN_INDEX_URL — skipping index fetch", config.indexUrl.isNotBlank())
        val entries = dataSource.fetchIndex(config.indexUrl)
        assertTrue(entries.size >= 0)
    }

    /** Fast reachability probe with a short timeout, so a down server skips quickly instead of hanging. */
    private fun reachable(serverUrl: String): Boolean = runCatching {
        runBlocking {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 3_000
                    connectTimeoutMillis = 3_000
                    socketTimeoutMillis = 3_000
                }
            }.use { probe ->
                val base = serverUrl.trim().removeSuffix("/")
                probe.get("$base/System/Info/Public").status.isSuccess()
            }
        }
    }.getOrDefault(false)
}
