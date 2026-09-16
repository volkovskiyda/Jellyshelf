package com.gmail.volkovskiyda.jellyshelf.live

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.remote.ApiSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.dataStore
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.grantJourneyPermissions
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.util.isUnauthorized
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * The metadata API path, against the real bot server: that the configured token is accepted, that
 * a wrong one is refused, that the documents it serves carry what the merge needs — and that a
 * sync which gains the API *upgrades* the library rather than disturbing it.
 *
 * **This is the only suite `.test.env`'s metadata API pair belongs to, and that is the design.**
 * Every other live test leaves `JELLYFIN_METADATA_API_URL` / `JELLYFIN_METADATA_API_TOKEN` alone,
 * so an ordinary live run is the *without* case: the app signing in, syncing and playing with no
 * metadata API configured at all, which is what an install that has never heard of the bot server
 * does. Filling the pair in adds this suite, which is the *with* case. Both halves of the split
 * therefore get exercised by running the same suite twice with one config file changed, rather
 * than by a flag no run ever flips.
 *
 * [SyncInstrumentedTest][com.gmail.volkovskiyda.jellyshelf.data.repository.SyncInstrumentedTest]
 * already pins the merge rules against a MockEngine that checks the bearer token itself, so
 * nothing here re-tests the arithmetic. What a mock cannot say is whether the *real* API answers
 * that shape at all: whether the token is the one the server wants, whether `fetchedAt` is present
 * (without it every API entry counts as oldest and the index silently wins every field it carries),
 * and whether the ids it keys on are the YouTube ids the library's Jellyfin paths parse to. Those
 * are the three ways this integration breaks in production without a single test turning red.
 *
 * Opt-in and tolerant, like [LiveEndpointTest]: no `.test.env`, no metadata API pair, or an
 * unreachable server all **skip**. Only a *half*-filled pair fails, since a URL with no token can
 * only ever produce 401s and silently skipping it would look exactly like the deliberate blank.
 *
 * Read-only against Jellyfin — the sync test writes to the device (the app's own database and
 * settings, wiped at both ends like [LiveUiJourneyTest] does) and never to the server.
 *
 * **`runBlocking`, not `runTest`**, for the reason `SyncInstrumentedTest` gives: a real sync parks
 * on real network and real Room, and `runTest`'s virtual clock treats every park as idle — which
 * fast-forwards straight through the auto-fill pass's own timeout budget and cancels it.
 */
@RunWith(AndroidJUnit4::class)
class LiveMetadataApiTest : KoinTest {

    private val config by inject<JellyfinTestConfig>()
    private val apiSource by inject<ApiSource>()
    private val indexSource by inject<IndexSource>()
    private val dataSource by inject<JellyfinDataSource>()
    private val library by inject<LibraryRepository>()
    private val settings by inject<SettingsRepository>()
    private val libraryFilterState by inject<LibraryFilterState>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Set by the sync test alone, so the endpoint tests leave the install's state untouched. */
    private var touchedAppState = false

    @Before
    fun setUp() {
        loadKoinModules(liveTestModule)
        assumeTrue("no .test.env config — skipping the live metadata API tests", config.isConfigured)
        // Loud, not skipped: half a pair is a typo in .test.env, and a run that skipped over it
        // would be indistinguishable from the deliberate both-blank state this suite opts out on.
        assertFalse(
            "JELLYFIN_METADATA_API_URL and JELLYFIN_METADATA_API_TOKEN must be set together — " +
                "a URL without its token can only produce 401s, and a token without a URL is " +
                "never sent anywhere. Fill both, or blank both to skip this suite.",
            config.metadataApiHalfConfigured,
        )
        assumeTrue(
            "no JELLYFIN_METADATA_API_URL/TOKEN — skipping the live metadata API tests. This is " +
                "the configured-off half of the with/without split: every other live test runs " +
                "this way on purpose.",
            config.hasMetadataApi,
        )
        assumeTrue(
            "Jellyfin server unreachable — skipping the live metadata API tests",
            serverReachable(config.serverUrl),
        )
    }

    @After
    fun tearDown() {
        try {
            if (touchedAppState) resetAppState()
        } finally {
            unloadKoinModules(liveTestModule)
        }
    }

    /**
     * The configured token, against the real API, through the app's own [ApiSource] — URL shape,
     * bearer header, HTTP stack and decoder all the shipped ones.
     *
     * An empty list is a pass: a bot server that has exported nothing yet is a valid state, and
     * the assertion that matters is that the request was *accepted* and the body decoded. What the
     * entries must look like when there are any is [liveEntries_carryTheFieldsTheMergeNeeds]'s job.
     */
    @Test
    fun fetchVideos_withTheConfiguredToken_isAcceptedAndDecodes() = runBlocking {
        val entries = apiSource.fetchVideos(config.metadataApiUrl, config.metadataApiToken)
        assertTrue(
            "the metadata API returned entries with a blank id — the merge keys on it",
            entries.all { it.id.isNotBlank() },
        )
    }

    /**
     * That the token is actually *enforced*. Without this the test above proves nothing: an API
     * that ignores authorization entirely would pass it with any string at all, including the
     * empty one a half-configured install sends.
     *
     * A 401 specifically, by the app's own [isUnauthorized] — that is the distinction sync turns
     * into `apiAuthFailed` and the settings screen turns into a red token field, and a server that
     * answered 403 or 500 instead would leave the user staring at "unreachable".
     *
     * Two rejected requests, which is worth knowing if anything in front of the bot server counts
     * failed auth attempts.
     */
    @Test
    fun fetchVideos_withAWrongOrMissingToken_isRejectedAsUnauthorized() = runBlocking {
        for ((label, token) in listOf("wrong" to "${config.metadataApiToken}-wrong", "blank" to "")) {
            val error = runCatching { apiSource.fetchVideos(config.metadataApiUrl, token) }.exceptionOrNull()
            assertNotNull("the metadata API accepted a $label token", error)
            assertTrue(
                "a $label token was refused with ${error!!::class.simpleName}: ${error.message} — " +
                    "sync reads a 401 as a configuration error (apiAuthFailed) and anything else " +
                    "as the API being unreachable, which points the user at the wrong thing",
                isUnauthorized(error),
            )
        }
    }

    /**
     * That the live documents carry the two fields the merge is built on.
     *
     * `fetchedAt` decides newest-wins per field. A server that stops emitting it does not fail
     * anything — every entry simply counts as oldest, the index quietly wins every field it has,
     * and API-only rows keep whatever they were first written with. That is invisible from the
     * app and invisible from every offline test, which uses entries this repo wrote itself.
     *
     * The ids are checked against the index's rather than against the library, because both feeds
     * key on the same YouTube id and a disagreement there means the two can never merge — the
     * exact case that produces a library where nothing is ever labelled "API + index". An index
     * that is unreachable or empty skips that half rather than failing it: the index is not this
     * suite's subject, and [LiveEndpointTest] is where its own health is asserted.
     */
    @Test
    fun liveEntries_carryTheFieldsTheMergeNeeds() = runBlocking {
        val entries = apiSource.fetchVideos(config.metadataApiUrl, config.metadataApiToken)
        assumeTrue("the metadata API exported no videos yet — nothing to check", entries.isNotEmpty())

        assertTrue(
            "no metadata API entry carries fetchedAt. The merge's newest-wins rule reads a " +
                "missing one as the epoch, so every index entry would outrank the API and " +
                "API-sourced fields would silently stop updating.",
            entries.any { it.fetchedAt != null },
        )
        assertTrue(
            "metadata API entries carry no titles — the merge would write rows with nothing in them",
            entries.any { !it.title.isNullOrBlank() },
        )

        val indexIds = runCatching { indexSource.fetchIndex(config.indexUrl) }.getOrNull()
            ?.map { it.id }?.toSet().orEmpty()
        assumeTrue("no reachable index to compare ids with", indexIds.isNotEmpty())
        assertTrue(
            "not one metadata API id appears in the index, so no video can ever merge the two " +
                "feeds. They key on different things — check the API is serving YouTube ids.",
            entries.any { it.id in indexIds },
        )
    }

    /**
     * The whole point of the suite: one real sync **without** the metadata API, then one **with**
     * it, over the same library — and the second must only ever add.
     *
     * Two passes rather than two tests, because the comparison *is* the assertion: what the second
     * sync does to rows the first one wrote is the merge rule (a feed-sourced row is downgraded
     * only when every feed that vouched for it was reachable and lacks the entry) meeting real
     * documents. Split across tests, each half would just be a sync that succeeded.
     *
     * Both passes drive [LibraryRepository.sync] directly rather than through the UI: the merge
     * and its provenance labels are the subject, and [LiveUiJourneyTest] already owns the question
     * of whether the buttons that start a sync work.
     *
     * Slow by nature — two full scoped syncs, each of which may also spend up to its auto-fill
     * budget on yt-dlp when the library has a handful of videos no feed describes.
     */
    @Test
    fun sync_withoutThenWithTheMetadataApi_onlyEverUpgradesProvenance() = runBlocking {
        assumeTrue(
            "no sync scope — set JELLYFIN_SYNC_FOLDER or JELLYFIN_SYNC_FOLDER_ID. Without one " +
                "this would sync a whole server, which is not something a test may set off.",
            config.hasSyncScope,
        )
        touchedAppState = true
        resetAppState()
        signInAndScope()

        val before = syncWithoutTheApi()
        val after = syncWithTheApi()
        assertOnlyUpgraded(before, after)
    }

    /** Pass 1: the way every other live test runs — the index configured, no metadata API. */
    private suspend fun syncWithoutTheApi(): List<Video> {
        settings.setIndexUrl(config.indexUrl)
        settings.setMetadataApi("", "")
        val result = library.sync().assertSuccess("the sync without the metadata API failed")
        assertFalse(
            "a blank metadata API URL was reported as degraded. Nothing is configured, so there " +
                "is nothing to be degraded about — this would put a permanent warning on the " +
                "settings line of every install that does not use the API.",
            result.apiDegraded,
        )
        assertFalse("a blank metadata API URL was reported as an auth failure", result.apiAuthFailed)

        val videos = library.observeVideos().first()
        check(videos.isNotEmpty()) {
            "the sync left the library empty — JELLYFIN_SYNC_FOLDER may hold no videos, or the " +
                "sync failed. Its error shows on the status line under Sync now."
        }
        assertTrue(
            "rows were labelled API-sourced by a sync with no metadata API configured",
            videos.none { it.metadataSource in API_SOURCES },
        )
        return videos
    }

    /** Pass 2: the same library and the same scope, with the metadata API now configured. */
    private suspend fun syncWithTheApi(): List<Video> {
        settings.setMetadataApi(config.metadataApiUrl, config.metadataApiToken)
        val result = library.sync().assertSuccess("the sync with the metadata API failed")
        assertFalse(
            "the metadata API was unreachable during the sync, so this run proved nothing about " +
                "the merge",
            result.apiDegraded,
        )
        assertFalse(
            "the metadata API rejected the configured token during the sync. It was accepted by " +
                "the direct fetch above, so the token sync sends is not the token .test.env holds.",
            result.apiAuthFailed,
        )
        return library.observeVideos().first()
    }

    /**
     * What the second sync was allowed to do to the first one's library: label rows API-sourced,
     * and nothing else.
     *
     * The per-row guard is the valuable one. A row that fell back to bare Jellyfin fields here is
     * the merge demoting metadata on a *reachable* feed's say-so — the one thing the downgrade
     * rule exists to prevent, and one that reaches the user as a video which silently lost its
     * channel, description and categories.
     */
    private fun assertOnlyUpgraded(before: List<Video>, after: List<Video>) {
        assertTrue(
            "the sync with the metadata API labelled no row API-sourced. Either none of the " +
                "scope's videos are in the API's export, or the ids do not line up with the " +
                "library's — see liveEntries_carryTheFieldsTheMergeNeeds.",
            after.any { it.metadataSource in API_SOURCES },
        )
        assertEquals(
            "adding the metadata API changed which videos are in the library. It is a metadata " +
                "feed — it has no business adding or removing rows.",
            before.map { it.youtubeId }.toSet(),
            after.map { it.youtubeId }.toSet(),
        )

        val sourceAfter = after.associate { it.youtubeId to it.metadataSource }
        val downgraded = before.filter { it.metadataSource != METADATA_SOURCE_JELLYFIN }
            .filter { sourceAfter[it.youtubeId] == METADATA_SOURCE_JELLYFIN }
        assertTrue(
            "the metadata API sync downgraded ${downgraded.size} described video(s) to bare " +
                "Jellyfin fields, e.g. ${downgraded.take(DOWNGRADE_SAMPLE).map { it.title }}",
            downgraded.isEmpty(),
        )
        assertTrue(
            "fewer videos carry metadata with the API configured (${after.described()}) than " +
                "without it (${before.described()})",
            after.described() >= before.described(),
        )
    }

    // --- Helpers ----------------------------------------------------------------------------

    private fun List<Video>.described() = count { it.metadataSource != METADATA_SOURCE_JELLYFIN }

    private fun SyncResult.assertSuccess(failure: String): SyncResult.Success = when (this) {
        is SyncResult.Success -> this
        is SyncResult.Error -> error("$failure: $message")
    }

    /**
     * Signs the app in and scopes it, the two things a sync needs and neither of which is this
     * suite's subject — so both are written straight to settings rather than typed into the UI,
     * which is [LiveUiJourneyTest]'s job.
     *
     * The sign-in goes through the app's own [JellyfinDataSource], i.e. the app's device id, so
     * the token and the `MediaBrowser` header agree exactly as they do after a real sign-in. That
     * also keeps this suite off the device ids the other live suites mint for themselves — it is
     * the app's session it takes out, and [resetAppState] drops it again at the end.
     *
     * With only `JELLYFIN_SYNC_FOLDER` configured, the path is resolved by browsing, one level at
     * a time, the same walk [LiveEndpointTest] makes.
     */
    private suspend fun signInAndScope() {
        grantJourneyPermissions()
        val auth = dataSource.authenticate(
            serverUrl = config.serverUrl,
            username = config.username,
            password = config.password,
        )
        settings.setConnection(config.serverUrl, apiKey = "")
        settings.setSession(auth.accessToken, auth.user.id, auth.user.name)

        val folderId = config.syncFolderId.ifBlank {
            resolveSyncFolderId(auth.accessToken, auth.user.id)
        }
        val folder = dataSource.getItem(config.serverUrl, auth.accessToken, auth.user.id, folderId)
        settings.setLibrary(folderId, folder.name.orEmpty())
    }

    /** `JELLYFIN_SYNC_FOLDER`'s id, by walking the path the in-app picker walks. */
    private suspend fun resolveSyncFolderId(token: String, userId: String): String {
        var parentId: String? = null
        var folderId = ""
        for (segment in config.syncFolder.split("/").map { it.trim() }.filter { it.isNotEmpty() }) {
            val children = dataSource.getChildFolders(config.serverUrl, token, userId, parentId)
            val match = children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: error("folder \"$segment\" of JELLYFIN_SYNC_FOLDER not found on the server")
            folderId = match.id
            parentId = folderId
        }
        return folderId
    }

    /**
     * Wipes what this suite shares with every other test on the device: the app's database, its
     * settings — the session this test signed in with included — and the process-lifetime library
     * filter, whose cached rows would otherwise flash a real server's videos into the next test.
     *
     * Deliberately the same wipe [LiveUiJourneyTest] performs, and for the same reason: a suite
     * that left a real session and a real library behind would change what the tests after it see.
     */
    private fun resetAppState() {
        libraryFilterState.query.value = ""
        libraryFilterState.lastVideos.value = null
        runBlocking {
            library.clearLocalData()
            // After clearLocalData, which writes to this same store.
            context.dataStore.edit { it.clear() }
        }
    }

    private companion object {
        /** The two provenance labels only a metadata API entry can produce. */
        val API_SOURCES = setOf(METADATA_SOURCE_API, METADATA_SOURCE_API_INDEX)

        /** Enough downgraded titles in the failure message to recognise the pattern, not a dump. */
        const val DOWNGRADE_SAMPLE = 5
    }
}
