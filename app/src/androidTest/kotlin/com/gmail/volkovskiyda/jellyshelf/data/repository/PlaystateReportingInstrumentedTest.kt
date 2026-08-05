package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.TestDemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun repository(settings: Settings = connected): DefaultLibraryRepository {
        val json = provideJson()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            sent += path to request.body.toByteArray().decodeToString()
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
            sources = LibrarySources(
                JellyfinDataSource(JellyfinClient(httpClient)),
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
        val row = db.videoDao().get("aaaaaaaaaaa")
        assertEquals(true, row?.played)
        assertEquals(0L, row?.playbackPositionTicks)
        assertEquals(1, row?.playCount)
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

    private companion object {
        const val SESSIONS_STOPPED = "/Sessions/Playing/Stopped"
        const val TICKS_PER_SECOND = 10_000_000L
        const val FIVE_MINUTES_TICKS = 300L * TICKS_PER_SECOND
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
    }
}
