package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.DefaultTimeProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.ApiSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.testJellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.fakeMetrics
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the repository tells Jellyfin when in-app playback stops, against a real in-memory Room
 * database and a MockEngine-backed [JellyfinDataSource] — the same harness shape as
 * [SyncInstrumentedTest], for the other half of the write path.
 *
 * These rules are invisible from the app: every one of them is a request that either goes out or
 * does not, and getting them wrong un-watches videos or leaves the server showing playback that
 * ended hours ago.
 *
 * `runBlocking`, not `runTest`, for the reason [SyncInstrumentedTest] documents at length: real
 * Room and real (mock-engine) HTTP work parks on other dispatchers, which a virtual clock treats
 * as idle. The reports here are fire-and-forget, so each case waits for the request it expects —
 * or, where it expects none, waits a beat and asserts nothing arrived.
 */
@RunWith(AndroidJUnit4::class)
class PlaystateReportingInstrumentedTest {

    private lateinit var db: JellyshelfDatabase

    /** Requests the repository actually sent, newest last: path plus decoded body. */
    private val sent = mutableListOf<Pair<String, String>>()

    private val connected = Settings(
        serverUrl = "http://server:8096",
        apiKey = "KEY",
        accessToken = "",
        userId = "user-1",
        userName = "User",
        libraryId = "",
        libraryName = "",
        indexUrl = "",
        lastSyncAt = 0L,
        lastSyncLibraryId = "",
    )

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val ioSequential = Dispatchers.Unconfined
        override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun ioScope(tag: String, failureMessage: String) =
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    /** A ten-minute video already carrying a resume point five minutes in. */
    private suspend fun seedVideo(
        youtubeId: String = "aaaaaaaaaaa",
        jellyfinItemId: String? = "jf-1",
        positionTicks: Long = FIVE_MINUTES_TICKS,
        played: Boolean = false,
    ) {
        db.videoDao().upsert(
            listOf(
                VideoEntity(
                    youtubeId = youtubeId,
                    jellyfinItemId = jellyfinItemId,
                    fileName = "Video [$youtubeId].mp4",
                    title = "Video",
                    channel = "Channel",
                    channelId = null,
                    durationSeconds = 600L,
                    uploadDate = null,
                    description = null,
                    tags = emptyList(),
                    youtubeCategories = emptyList(),
                    thumbnailUrl = null,
                    played = played,
                    playbackPositionTicks = positionTicks,
                    playCount = 0,
                    lastSyncedAt = 0L,
                ),
            ),
        )
    }

    /**
     * [beforeResponse] runs after the request is recorded but before it is answered, so a test that
     * cares about what happens *during* a request can hold one open and act while it is in flight.
     */
    private fun repository(
        settings: Settings = connected,
        beforeResponse: suspend (String) -> Unit = {},
    ): DefaultLibraryRepository {
        val json = provideJson()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            sent += path to request.body.toByteArray().decodeToString()
            beforeResponse(path)
            when {
                // The mirror fetch after a partway in-app stop.
                path.startsWith("/Items/") -> respond(
                    content = """
                        {"Id":"jf-1","Name":"Video",
                         "UserData":{"Played":true,"PlaybackPositionTicks":0,"PlayCount":1}}
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DefaultLibraryRepository(
            db = db,
            settings = FakeSettingsRepository(settings),
            dispatchers = dispatchers,
            time = DefaultTimeProvider(),
            metrics = fakeMetrics(),
            sources = LibrarySources(
                testJellyfinDataSource(httpClient),
                ApiSource(httpClient, dispatchers, json),
                IndexSource(context, httpClient, dispatchers, json),
                YtDlpMetadataSource(context, dispatchers, json),
                TestDemoBackend(IndexSource(context, httpClient, dispatchers, json)),
            ),
        )
    }

    /** Waits for a request to [path], or gives up — so a missing report fails as a null, not a hang. */
    private suspend fun awaitRequest(path: String): Pair<String, String>? = withTimeoutOrNull(WAIT_MS) {
        while (sent.none { it.first == path }) delay(POLL_MS)
        sent.first { it.first == path }
    }

    /**
     * Waits for the row to satisfy [predicate], the same way [awaitRequest] waits for a request.
     *
     * A request landing is not the write landing: what follows the response is a JSON parse and two
     * Room queries on Room's own executor, so asserting the row the instant the request is recorded
     * is a race the test would lose under load rather than a fact it checks.
     */
    private suspend fun awaitRow(
        youtubeId: String = "aaaaaaaaaaa",
        predicate: (VideoEntity) -> Boolean,
    ): VideoEntity? = withTimeoutOrNull(WAIT_MS) {
        var row = db.videoDao().get(youtubeId)
        while (row == null || !predicate(row)) {
            delay(POLL_MS)
            row = db.videoDao().get(youtubeId)
        }
        row
    }

    /** Long enough for a report that was going to be sent to have been sent. */
    private suspend fun letReportsSettle() = delay(SETTLE_MS)

    private fun bodyOf(request: Pair<String, String>) = Json.Default.parseToJsonElement(request.second).jsonObject

    @Test
    fun belowTheResumeBar_closesTheSessionAndLeavesEveryStoredPositionAlone() = runBlocking {
        seedVideo()
        val repo = repository()

        // 55s into a ten-minute video: under the bar of min(duration/10, 60s), so nothing is
        // recorded — but the ticks that got there opened a session, and it has to be closed.
        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 55_000L,
            completed = false,
            playSessionId = "ps-1",
        )

        val stop = awaitRequest(SESSIONS_STOPPED)
        assertNotNull("expected a session stop, got $sent", stop)
        val body = bodyOf(stop!!)
        assertEquals("jf-1", body["ItemId"]?.jsonPrimitive?.content)
        assertEquals("ps-1", body["PlaySessionId"]?.jsonPrimitive?.content)
        // Where playback actually stopped — not the resume point the row is holding on to.
        assertEquals(55L * TICKS_PER_SECOND, body["PositionTicks"]?.jsonPrimitive?.long)

        letReportsSettle()
        assertEquals("the session close is the only report", listOf(SESSIONS_STOPPED), sent.map { it.first })
        assertEquals(FIVE_MINUTES_TICKS, db.videoDao().get("aaaaaaaaaaa")?.playbackPositionTicks)
    }

    @Test
    fun belowTheResumeBar_withNoSessionOpen_reportsNothingAtAll() = runBlocking {
        seedVideo()
        val repo = repository()

        // An external player's result: no session was ever opened, so there is nothing to close.
        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 55_000L,
            completed = false,
            playSessionId = null,
        )

        letReportsSettle()
        assertTrue("expected silence, got $sent", sent.isEmpty())
        assertEquals(FIVE_MINUTES_TICKS, db.videoDao().get("aaaaaaaaaaa")?.playbackPositionTicks)
    }

    @Test
    fun aPartwayInAppStop_closesTheSessionInsteadOfWritingPlayedFalse() = runBlocking {
        seedVideo(positionTicks = 0L)
        val repo = repository()

        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 300_000L,
            completed = false,
            playSessionId = "ps-1",
        )

        assertNotNull("expected a session stop, got $sent", awaitRequest(SESSIONS_STOPPED))
        letReportsSettle()
        // The carrier swap this whole feature rests on: a direct user-data write would carry
        // Played=false over a mark the server's own threshold may have just made.
        assertNull(sent.map { it.first }.firstOrNull { it.endsWith("/UserData") })

        // And the server's verdict comes back into the row rather than waiting for a sync — the
        // play count with it, since the stamp this write leaves makes the next sync keep all three.
        assertNotNull("expected the mirror fetch, got $sent", awaitRequest("/Items/jf-1"))
        val row = awaitRow { it.played }
        assertNotNull("the server's verdict never reached the row", row)
        assertEquals(0L, row?.playbackPositionTicks)
        assertEquals(1, row?.playCount)
    }

    /**
     * The mirror fetch is the one server read the app makes off the back of its own write, and it
     * runs holding no playstate lock — so a hand toggle can land in the middle of it. When it does,
     * the row has moved past the report the server was asked about, and the answer is stale before
     * it arrives: the toggle is what the user just said, the verdict is what the server made of a
     * stop it hadn't heard about yet.
     *
     * Both halves of that are checked here, because both are load-bearing and neither is visible in
     * the types: that the toggle can complete at all while the fetch is open (it would deadlock
     * behind the fetch if the mirror still held `playstateMutex`), and that the verdict is then
     * declined rather than written over it.
     */
    @Test
    fun aToggleDuringTheMirrorFetch_keepsTheToggleAndDropsTheServersVerdict() = runBlocking {
        seedVideo(positionTicks = 0L)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val repo = repository { path ->
            if (path.startsWith("/Items/")) {
                fetchStarted.complete(Unit)
                releaseFetch.await()
            }
        }

        // A partway in-app stop: played=false, position 5:00 written locally, then the read-back
        // that asks the server what it made of it. That fetch is what this test freezes.
        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 300_000L,
            completed = false,
            playSessionId = "ps-1",
        )
        assertNotNull(
            "the mirror fetch never started, got $sent",
            withTimeoutOrNull(WAIT_MS) { fetchStarted.await() },
        )

        // Marked watched by hand with the fetch still open. A null here is the deadlock case, not a
        // slow machine: nothing else in this test can release the toggle.
        val toggled = withTimeoutOrNull(WAIT_MS) { repo.setPlayed("aaaaaaaaaaa", played = true) }
        assertEquals("the toggle blocked behind the mirror fetch", true, toggled)
        assertEquals(true, db.videoDao().get("aaaaaaaaaaa")?.played)

        releaseFetch.complete(Unit)
        letReportsSettle()

        // The server answered played, position 0, count 1 — all of it dropped. Played matching is
        // a coincidence of this scenario; the position and the count are what tell the two apart.
        val row = db.videoDao().get("aaaaaaaaaaa")
        assertEquals(true, row?.played)
        assertEquals("the toggle's position must survive", FIVE_MINUTES_TICKS, row?.playbackPositionTicks)
        assertEquals("the server's play count must not land", 0, row?.playCount)
    }

    /**
     * The ordering the guard above rests on: a toggle reaches disk before its server write leaves.
     *
     * That is what makes "the row still matches what I reported" a sound test of "nothing has
     * happened since" — a toggle that sent first would be able to move the server's answer while
     * the row still looked untouched, and the mirror would accept a verdict formed through a write
     * it can't see. Nothing in the types says so today; it is the order of two statements in
     * [PlaystateWriter.setPlayed], which is exactly the kind of thing a later edit reorders.
     */
    @Test
    fun aToggleIsOnDiskBeforeItsServerWriteGoesOut() = runBlocking {
        seedVideo()
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val repo = repository { path ->
            if (path.endsWith("/PlayedItems/jf-1")) {
                sendStarted.complete(Unit)
                releaseSend.await()
            }
        }

        val toggle = async { repo.setPlayed("aaaaaaaaaaa", played = true) }
        assertNotNull(
            "the played-items write never went out, got $sent",
            withTimeoutOrNull(WAIT_MS) { sendStarted.await() },
        )

        // Frozen mid-send: the local row already carries the toggle.
        assertEquals(true, db.videoDao().get("aaaaaaaaaaa")?.played)

        releaseSend.complete(Unit)
        assertTrue(toggle.await())
    }

    @Test
    fun aDemoRowNeverReachesTheServer() = runBlocking {
        seedVideo(jellyfinItemId = "demo")
        val repo = repository()

        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 55_000L,
            completed = false,
            playSessionId = "ps-1",
        )

        letReportsSettle()
        assertTrue("a demo row has no server behind it, got $sent", sent.isEmpty())
    }

    /**
     * The same for the stop that actually records something. `DEMO_ITEM_ID` is documented as the
     * second line of defence behind "a demo install has no credentials" — so it has to hold on the
     * branch that reaches the server carriers, not only on the one that closes a session.
     */
    @Test
    fun aDemoRowThatRecordsAResumePointStillNeverReachesTheServer() = runBlocking {
        seedVideo(jellyfinItemId = "demo", positionTicks = 0L)
        val repo = repository()

        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 300_000L,
            completed = false,
            playSessionId = "ps-1",
        )

        letReportsSettle()
        assertTrue("a demo row has no server behind it, got $sent", sent.isEmpty())
        // The local resume point is still recorded — the demo library resumes like any other.
        assertEquals(FIVE_MINUTES_TICKS, db.videoDao().get("aaaaaaaaaaa")?.playbackPositionTicks)
    }

    /** And for the manual toggle, the one watch-state write that isn't a playback report at all. */
    @Test
    fun aDemoRowMarkedWatchedByHandStillNeverReachesTheServer() = runBlocking {
        seedVideo(jellyfinItemId = "demo")
        val repo = repository()

        // True: nothing failed. There was no server write to warn the user about.
        assertTrue(repo.setPlayed("aaaaaaaaaaa", played = true))

        letReportsSettle()
        assertTrue("a demo row has no server behind it, got $sent", sent.isEmpty())
        assertEquals(true, db.videoDao().get("aaaaaaaaaaa")?.played)
    }

    /** And for a demo video watched to the end, whose carrier is the played-items endpoint. */
    @Test
    fun aFinishedDemoRowStillNeverReachesTheServer() = runBlocking {
        seedVideo(jellyfinItemId = "demo")
        val repo = repository()

        repo.reportPlaybackStopped(
            youtubeId = "aaaaaaaaaaa",
            positionMs = 600_000L,
            completed = true,
            playSessionId = null,
        )

        letReportsSettle()
        assertTrue("a demo row has no server behind it, got $sent", sent.isEmpty())
        assertEquals(true, db.videoDao().get("aaaaaaaaaaa")?.played)
    }

    private companion object {
        const val SESSIONS_STOPPED = "/Sessions/Playing/Stopped"
        const val TICKS_PER_SECOND = 10_000_000L
        const val FIVE_MINUTES_TICKS = 300L * TICKS_PER_SECOND
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
    }
}
