package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Pins [decodeIndexEntries]'s failure contract: a body that is not a JSON array of index entries
 * comes out as an [IOException], never as a [SerializationException] and never as an empty list.
 * A legitimate index is always an array (`scripts/build-library-index.sh` emits `[]` at minimum),
 * so a 200 carrying anything else — a captive portal's sign-in page, a file caught mid-rewrite, an
 * empty body — has to count as a failed fetch, or the sync would downgrade every index-sourced row.
 *
 * The catch behind it relies on every one of these failures being a [SerializationException].
 * That held through kotlinx.serialization 1.12.0, which wrapped decoding failures in a new
 * JsonException, and through 1.13.0, which reverted that — the types underneath moved twice in two
 * releases. These cases fail at `./gradlew test` speed if a bump moves them out of that hierarchy.
 *
 * Decoded with [provideJson], the configuration the app injects, not a copy.
 */
class IndexDecodingTest {

    private val json = provideJson()

    private fun decode(body: String): List<IndexEntry> =
        decodeIndexEntries(json, ByteArrayInputStream(body.toByteArray()))

    private fun assertFailedFetch(body: String) {
        val e = assertThrows("'$body' must fail the fetch", IOException::class.java) { decode(body) }
        assertTrue(
            "'$body' should keep the parse error as the cause, got ${e.cause}",
            e.cause is SerializationException,
        )
    }

    @Test
    fun emptyBodyIsAFailedFetch() = assertFailedFetch("")

    @Test
    fun blankBodyIsAFailedFetch() = assertFailedFetch("  \n")

    @Test
    fun htmlBodyIsAFailedFetch() = assertFailedFetch("<html><body>Sign in to continue</body></html>")

    @Test
    fun truncatedArrayIsAFailedFetch() = assertFailedFetch("""[{"id":"aaaaaaaaaaa","title":"Cut o""")

    @Test
    fun objectInsteadOfArrayIsAFailedFetch() = assertFailedFetch("{}")

    @Test
    fun jsonNullIsAFailedFetch() = assertFailedFetch("null")

    /** Thrown by the generated serializer rather than the parser — the path 1.12.0 rewrapped. */
    @Test
    fun entryWithoutAnIdIsAFailedFetch() = assertFailedFetch("""[{"title":"No id"}]""")

    @Test
    fun wronglyTypedFieldIsAFailedFetch() = assertFailedFetch("""[{"id":"aaaaaaaaaaa","duration":"long"}]""")

    /** The smallest legitimate index: an empty library. */
    @Test
    fun emptyArrayIsAnEmptyIndex() {
        assertEquals(emptyList<IndexEntry>(), decode("[]"))
    }

    /** A key this app does not model yet is a newer index, not a broken one. */
    @Test
    fun unknownKeysAreTolerated() {
        assertEquals(
            listOf(IndexEntry(id = "aaaaaaaaaaa", title = "Known")),
            decode("""[{"id":"aaaaaaaaaaa","title":"Known","addedByANewerScript":1}]"""),
        )
    }

    /** The stream is the HTTP response body, so a failed decode must not leak it. */
    @Test
    fun streamIsClosedWhenDecodingFails() {
        var closed = false
        val stream = object : ByteArrayInputStream("<html>".toByteArray()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertThrows(IOException::class.java) { decodeIndexEntries(json, stream) }
        assertTrue("the body stream was left open", closed)
    }
}
