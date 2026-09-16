package com.gmail.volkovskiyda.jellyshelf.live

import com.gmail.volkovskiyda.jellyshelf.data.remote.AuthenticationResult
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Authenticates the way the app does — username + password through `AuthenticateByName` with the
 * `MediaBrowser` header — and drives everything after with the returned user-scoped token. No
 * admin-only endpoint is touched (`GET /Users` needs admin), so the credentials can and should be
 * a dedicated non-admin test user.
 *
 * Uses the real [JellyfinClient]/[JellyfinDataSource] from `appModule` (tuned base Ktor client +
 * ContentNegotiation + kotlinx `Json` + timeouts), so it exercises the actual migrated stack.
 *
 * Read-only by design: every call here leaves the server exactly as it found it, so this class can
 * run against any configured server without a thought. The write endpoints (setPlayed, the session
 * reports, updateUserData) are driven by [LiveUiJourneyTest] instead — through the app's own UI,
 * against one designated item, and undone afterwards.
 */
class LiveEndpointTest : KoinTest {

    private val config by inject<JellyfinTestConfig>()
    private val baseHttpClient by inject<HttpClient>()
    private val deviceInfo by inject<DeviceInfo>()

    /**
     * Reaches Jellyfin as one stable device on the Jellyfin dashboard per suite rather than one per run.
     * See [liveJellyfinClient].
     */
    private val jellyfinClient by lazy { liveJellyfinClient(baseHttpClient, deviceInfo, DEVICE_ID) }
    private val dataSource by lazy { JellyfinDataSource(jellyfinClient) }
    private val indexSource by inject<IndexSource>()

    @Before
    fun setUp() {
        loadKoinModules(liveTestModule)
        assumeTrue("no .test.env config — skipping live-endpoint test", config.isConfigured)
        assumeTrue(
            "Jellyfin server unreachable — skipping live-endpoint test",
            serverReachable(config.serverUrl),
        )
    }

    @After
    fun tearDown() = unloadKoinModules(liveTestModule)

    /**
     * The app's sign-in exchange, verbatim — the `MediaBrowser` header included, which the data
     * source now builds itself off the install's persisted DeviceId. That id is stable for the
     * life of the install, so Jellyfin's dashboard still shows one "device" across live runs, and
     * it is the same one the app itself signs in with.
     */
    private suspend fun signIn(): AuthenticationResult = dataSource.authenticate(
        serverUrl = config.serverUrl,
        username = config.username,
        password = config.password,
    )

    @Test
    fun authenticateByName_returnsTokenForUser() = runTest {
        val auth = signIn()
        assertTrue(auth.accessToken.isNotBlank())
        assertTrue(auth.user.id.isNotBlank())
    }

    @Test
    fun getViews_forSignedInUser_deserializes() = runTest {
        val auth = signIn()
        val api = jellyfinClient.create(config.serverUrl, auth.accessToken)
        // Round-tripping without throwing is the assertion: proves ItemsResponse/BaseItemDto decode.
        val views = api.getViews(auth.user.id)
        assertTrue(views.items.size >= 0)
    }

    @Test
    fun getItems_forSignedInUser_deserializes() = runTest {
        val auth = signIn()
        val api = jellyfinClient.create(config.serverUrl, auth.accessToken)
        val resp = api.getItems(userId = auth.user.id, limit = 5)
        assertTrue(resp.items.size >= 0)
    }

    @Test
    fun getChildFolders_topLevelViews_deserialize() = runTest {
        val auth = signIn()
        // A blank parentId returns the user's top-level collections (views); the point is that the
        // paged BaseItemDto list deserializes without throwing.
        val folders = dataSource.getChildFolders(config.serverUrl, auth.accessToken, auth.user.id, parentId = null)
        assertTrue(folders.size >= 0)
    }

    @Test
    fun syncFolder_resolvesByPathAndListsItems() = runTest {
        assumeTrue("no JELLYFIN_SYNC_FOLDER — skipping sync-scope test", config.syncFolder.isNotBlank())
        val auth = signIn()

        // Walk the picker's path ("Home Videos/YouTube") one browse level at a time, exactly the
        // way the in-app folder browser reaches it.
        var parentId: String? = null
        var folderId = ""
        for (segment in config.syncFolder.split("/").map { it.trim() }.filter { it.isNotEmpty() }) {
            val children = dataSource.getChildFolders(config.serverUrl, auth.accessToken, auth.user.id, parentId)
            val match = children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
            assertTrue("folder \"$segment\" of JELLYFIN_SYNC_FOLDER not found on server", match != null)
            folderId = match!!.id
            parentId = folderId
        }

        // When the id is also pinned in .test.env (for UI tests), the resolved folder must be it.
        if (config.syncFolderId.isNotBlank()) {
            assertEquals(
                "JELLYFIN_SYNC_FOLDER_ID does not match the folder JELLYFIN_SYNC_FOLDER resolves to",
                config.syncFolderId,
                folderId,
            )
        }

        // A scoped item listing — what a scoped sync issues — deserializes.
        val api = jellyfinClient.create(config.serverUrl, auth.accessToken)
        val scoped = api.getItems(userId = auth.user.id, parentId = folderId, limit = 5)
        assertTrue(scoped.items.size >= 0)
    }

    @Test
    fun fetchIndex_atConfiguredOrDefaultUrl_deserializes() = runTest {
        // config.indexUrl falls back to <server>/jellyshelf-index.json when JELLYFIN_INDEX_URL
        // is unset — the same convention the app's "fill from server" affordance applies.
        val entries = indexSource.fetchIndex(config.indexUrl)
        assertTrue(entries.size >= 0)
    }

    private companion object {
        /** This suite's Jellyfin device id — fixed, and distinct from every other live suite's. */
        const val DEVICE_ID = "jellyshelf-live-test"
    }
}
