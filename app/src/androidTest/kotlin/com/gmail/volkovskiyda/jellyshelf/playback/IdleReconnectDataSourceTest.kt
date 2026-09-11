@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * [IdleReconnectDataSource]'s one job: notice that a stream has gone untouched for too long and
 * reopen it at the byte it had reached, without ExoPlayer ever being told.
 *
 * What makes this worth pinning is that every way of getting it wrong is silent. Reopening at the
 * wrong offset does not throw — it feeds the extractor bytes from somewhere else in the file, which
 * surfaces as corrupt video or desynchronised audio much later. Failing to reopen at all restores
 * exactly the bug the class exists for, which needs a real network and a ten-minute pause to see.
 * Reopening when nothing was idle costs a round trip on a healthy stream. None of those fail a
 * build; all of them fail here.
 *
 * Instrumented rather than a JVM test for the same reason [PlaybackResolveTest] is: [DataSpec] is
 * built around [Uri], which is an Android class, and this project deliberately has no Robolectric.
 * Nothing here needs a server, a player or a device state — the upstream and the clock are both
 * fakes.
 */
@RunWith(AndroidJUnit4::class)
class IdleReconnectDataSourceTest {

    private val uri = "https://example.test/Videos/abc/stream?static=true".toUri()

    /** Distinct byte values, so a wrong reopen offset shows up as the wrong content. */
    private val data = ByteArray(64) { it.toByte() }

    private var now = 0L

    private fun spec(position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    private fun source(upstream: DataSource) =
        IdleReconnectDataSource(upstream, idleReopenMs = IDLE_MS) { now }

    @Test
    fun doesNotReopenWhileReadsKeepComing() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec())
        val buffer = ByteArray(4)

        // Just short of the threshold, twice over: the gap is measured from the previous read, not
        // from the open, so two 29-second gaps in a row must still not trip it.
        now += IDLE_MS - 1_000
        source.read(buffer, 0, 4)
        now += IDLE_MS - 1_000
        source.read(buffer, 0, 4)

        assertEquals("no reopen expected", 1, upstream.openedSpecs.size)
    }

    @Test
    fun reopensAtTheByteItHadReached() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec())
        val buffer = ByteArray(4)
        source.read(buffer, 0, 4)
        assertArrayEquals(data.copyOfRange(0, 4), buffer)

        now += IDLE_MS + 1_000
        source.read(buffer, 0, 4)

        assertEquals("expected one reopen", 2, upstream.openedSpecs.size)
        assertEquals("reopened at the wrong byte", 4L, upstream.openedSpecs[1].position)
        // The whole point: the caller sees a continuous stream across the reconnect.
        assertArrayEquals("bytes did not continue", data.copyOfRange(4, 8), buffer)
    }

    @Test
    fun reopenKeepsTheRemainingLengthOfABoundedRequest() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec(position = 10, length = 20))
        val buffer = ByteArray(4)
        source.read(buffer, 0, 4)

        now += IDLE_MS + 1_000
        source.read(buffer, 0, 4)

        val reopened = upstream.openedSpecs[1]
        // Position is the original plus what was consumed; length is what is left of the range.
        assertEquals(14L, reopened.position)
        assertEquals(16L, reopened.length)
        assertArrayEquals(data.copyOfRange(14, 18), buffer)
    }

    @Test
    fun reopensAStreamThatWentIdleBeforeItsFirstRead() {
        // The preloaded next video: opened, a few seconds buffered, then untouched for the whole
        // pause. Nothing has been read, so the reopen asks for the same range again.
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec(position = 8))

        now += IDLE_MS + 1_000
        val buffer = ByteArray(4)
        source.read(buffer, 0, 4)

        assertEquals(2, upstream.openedSpecs.size)
        assertEquals(8L, upstream.openedSpecs[1].position)
        assertArrayEquals(data.copyOfRange(8, 12), buffer)
    }

    @Test
    fun aFailingReopenPropagates() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec())
        val buffer = ByteArray(4)
        source.read(buffer, 0, 4)

        now += IDLE_MS + 1_000
        upstream.failNextOpen = IOException("connection refused")
        try {
            source.read(buffer, 0, 4)
            fail("a failing reopen must surface, so ExoPlayer's own retry can open a fresh stream")
        } catch (expected: IOException) {
            assertEquals("connection refused", expected.message)
        }
    }

    @Test
    fun neverReopensAfterTheEndOfTheData() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec(position = 60))
        val buffer = ByteArray(8)
        // Drain the last four bytes, then take the end-of-input.
        source.read(buffer, 0, 8)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 8))

        now += IDLE_MS + 1_000
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 8))

        assertEquals("nothing left to fetch, so nothing to reopen", 1, upstream.openedSpecs.size)
    }

    @Test
    fun reopeningIsForgottenAcrossCloseAndOpen() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec())
        val buffer = ByteArray(4)
        source.read(buffer, 0, 4)
        source.close()

        // A seek: a fresh open, and the idle clock and byte count start over with it.
        now += IDLE_MS + 1_000
        source.open(spec(position = 32))
        source.read(buffer, 0, 4)

        assertEquals("the fresh open must not be followed by a reopen", 2, upstream.openedSpecs.size)
        assertArrayEquals(data.copyOfRange(32, 36), buffer)
    }

    @Test
    fun aZeroLengthReadIsNeitherActivityNorAReasonToReopen() {
        val upstream = FakeUpstream(data)
        val source = source(upstream)
        source.open(spec())
        val buffer = ByteArray(4)

        now += IDLE_MS + 1_000
        assertEquals(0, source.read(buffer, 0, 0))
        assertEquals("a zero-length read must not reopen", 1, upstream.openedSpecs.size)

        // ...and it must not have counted as contact with the socket either, so the next real read
        // still sees the stream as stale.
        source.read(buffer, 0, 4)
        assertEquals(2, upstream.openedSpecs.size)
    }

    private companion object {
        /** The production default is thirty seconds; the tests pin their own so it can move. */
        const val IDLE_MS = 30_000L
    }
}

/**
 * An upstream that serves [data] over whatever range it is opened with and records every open, so a
 * test can assert what the reconnect asked for.
 */
@Suppress("EmptyFunctionBlock")
private class FakeUpstream(private val data: ByteArray) : DataSource {

    val openedSpecs = mutableListOf<DataSpec>()

    /** Consumed by the next [open], which throws it instead of connecting. */
    var failNextOpen: IOException? = null

    private var position = 0
    private var limit = 0

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun getUri(): Uri? = openedSpecs.lastOrNull()?.uri

    override fun open(dataSpec: DataSpec): Long {
        failNextOpen?.let {
            failNextOpen = null
            throw it
        }
        openedSpecs += dataSpec
        position = dataSpec.position.toInt()
        limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            data.size
        } else {
            minOf(data.size.toLong(), dataSpec.position + dataSpec.length).toInt()
        }
        return (limit - position).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= limit) return C.RESULT_END_OF_INPUT
        val count = minOf(length, limit - position)
        data.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun close() {
        position = 0
        limit = 0
    }
}
