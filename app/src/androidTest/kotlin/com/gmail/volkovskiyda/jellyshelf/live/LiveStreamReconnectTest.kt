@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.live

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ktor.KtorDataSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.di.MEDIA_HTTP_CLIENT
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.mediaBrowserTokenHeader
import com.gmail.volkovskiyda.jellyshelf.playback.IdleReconnectDataSource
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.core.qualifier.named
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * That [IdleReconnectDataSource] can pick a real Jellyfin stream back up mid-file, over whatever
 * sits in front of the server.
 *
 * **Why this cannot be a fake.** The reconnect's whole correctness rests on a byte offset and on the
 * server honouring the range that carries it. Getting that wrong does not throw: a reopen that asks
 * from zero, or that miscounts what it had already consumed, returns perfectly valid bytes from the
 * wrong place, which reaches the extractor as corrupt video or desynchronised audio some seconds
 * later and reaches a test as nothing at all. `IdleReconnectDataSourceTest` pins the arithmetic
 * against a fake upstream; only this pins the half that belongs to the server — that a second
 * ranged GET, on a connection the first one did not use, resumes the same bytes.
 *
 * It is deliberately not driven through the player. Whether a paused video's loader actually
 * performs a read after resuming depends on how deep its buffer happens to be, which depends on the
 * file's bitrate — `LiveUiJourneyTest` pauses long enough to go stale and proves playback survives
 * it, but cannot promise it exercised a reconnect. Driving the datasource directly makes the
 * reconnect the subject rather than a hoped-for side effect, and costs two ranged reads.
 *
 * The clock is injected, so the idle wait is a variable being set rather than time being spent.
 *
 * Read-only, opt-in and tolerant of a down server, exactly like [LiveEndpointTest]: no `.test.env`,
 * an unreachable server or a library with no video all skip rather than fail.
 */
class LiveStreamReconnectTest : KoinTest {

    private val config by inject<JellyfinTestConfig>()
    private val baseHttpClient by inject<HttpClient>()
    private val deviceInfo by inject<DeviceInfo>()

    /**
     * Reaches Jellyfin as its own device, so its session is never the app's or another live suite's.
     * See [liveJellyfinClient].
     */
    private val jellyfinClient by lazy { liveJellyfinClient(baseHttpClient, deviceInfo, DEVICE_ID) }
    private val dataSource by lazy { JellyfinDataSource(jellyfinClient) }
    private val mediaHttpClient by inject<HttpClient>(named(MEDIA_HTTP_CLIENT))

    @Before
    fun setUp() {
        loadKoinModules(liveTestModule)
        assumeTrue("no .test.env config — skipping the live reconnect test", config.isConfigured)
        // Scoped like the app, not like the server. An unscoped scan reaches whatever else the
        // server holds, and a library can contain items Jellyfin itself cannot stream — one such
        // `.mkv` answered a perfectly well-formed ranged request with a 500 while this test was
        // being written, which says nothing about reconnecting. The sync folder is the content the
        // app actually plays.
        assumeTrue(
            "no JELLYFIN_SYNC_FOLDER_ID — skipping the live reconnect test",
            config.syncFolderId.isNotBlank(),
        )
        assumeTrue(
            "Jellyfin server unreachable — skipping the live reconnect test",
            serverReachable(config.serverUrl),
        )
    }

    @After
    fun tearDown() = unloadKoinModules(liveTestModule)

    @Test
    fun reconnectingMidStream_resumesTheSameBytes() = runTest {
        val auth = dataSource.authenticate(
            serverUrl = config.serverUrl,
            username = config.username,
            password = config.password,
        )
        val api = jellyfinClient.create(config.serverUrl, auth.accessToken)
        // getItems already narrows to video item types; a folder or a zero-length entry would have
        // nothing to stream.
        val video = api.getItems(
            userId = auth.user.id,
            parentId = config.syncFolderId,
            limit = ITEM_SCAN_LIMIT,
        ).items.firstOrNull { it.isFolder != true && (it.runTimeTicks ?: 0L) > 0L }
        assumeTrue("no playable video in the library — skipping the live reconnect test", video != null)
        val url = checkNotNull(video).let {
            Playback.streamUrl(config.serverUrl, it.id, credential = null)
        }

        // What the second half of the read must equal: the same range, fetched on its own.
        val expected = ByteArray(CHUNK_BYTES)
        val resumeAt = START + CHUNK_BYTES
        streamSource(auth.accessToken)
            .use(DataSpec.Builder().setUri(url.toUri()).setPosition(resumeAt).build()) {
                it.readFully(expected)
            }
        assertTrue("the server returned no bytes at all", expected.any { byte -> byte != 0.toByte() })

        val first = ByteArray(CHUNK_BYTES)
        val afterReconnect = ByteArray(CHUNK_BYTES)
        var now = 0L
        val source = IdleReconnectDataSource(streamSource(auth.accessToken), IDLE_MS) { now }
        source.use(DataSpec.Builder().setUri(url.toUri()).setPosition(START).build()) {
            it.readFully(first)
            // The pause: nothing is read, and by the time the next read comes the connection has
            // been sitting unused for longer than a stream is trusted to survive.
            now += IDLE_MS + 1_000
            it.readFully(afterReconnect)
        }

        assertArrayEquals(
            "the reconnect resumed at the wrong offset — playback would continue with the wrong bytes",
            expected,
            afterReconnect,
        )
    }

    /** A media source built exactly as `PlaybackService` builds one, credential header included. */
    private fun streamSource(token: String): DataSource = KtorDataSource.Factory(mediaHttpClient)
        .setDefaultRequestProperties(mapOf(Playback.TOKEN_HEADER to mediaBrowserTokenHeader(token)))
        .createDataSource()

    /** Opens [spec], runs [block], and closes even when it throws — the [DataSource] contract. */
    private inline fun <T : DataSource> T.use(spec: DataSpec, block: (T) -> Unit) {
        try {
            open(spec)
            block(this)
        } finally {
            close()
        }
    }

    /**
     * Fills [buffer] completely. A single `read` may return less than asked for — the contract
     * promises progress, not a full buffer — and a test comparing a short read against a full
     * expectation would fail for a reason that has nothing to do with reconnecting.
     */
    private fun DataSource.readFully(buffer: ByteArray) {
        var filled = 0
        while (filled < buffer.size) {
            val read = read(buffer, filled, buffer.size - filled)
            check(read != C.RESULT_END_OF_INPUT) { "stream ended after $filled of ${buffer.size} bytes" }
            filled += read
        }
    }

    private companion object {
        /** This suite's Jellyfin device id — fixed, and distinct from every other live suite's. */
        const val DEVICE_ID = "jellyshelf-live-reconnect-test"

        /** How far into the file to start, so the reconnect is a genuine mid-file resume. */
        const val START = 1_000_000L

        /** Enough bytes either side of the reconnect to be certain of the offset, and no more. */
        const val CHUNK_BYTES = 64 * 1024

        /** Past `IdleReconnectDataSource`'s own threshold; the clock here is a variable, not a wait. */
        const val IDLE_MS = 30_000L

        const val ITEM_SCAN_LIMIT = 20
    }
}
