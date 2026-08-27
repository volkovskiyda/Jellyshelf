package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [resolveQueue]'s index safety — the whole reason the batched queue read is allowed to exist.
 *
 * Resolving a queue used to be one Room `Flow` collection per item; it is now one `IN (...)` query
 * for the batch. That trade buys ~600 ms on a library-sized queue and brings four ways to be
 * silently wrong with it, of which this file pins the two that survive into the mapping:
 *
 *  - rows come back in **arbitrary order**, so the queue must be built by walking the *input*;
 *  - ids with **no row are simply absent**, so a miss must stay a queue entry rather than vanish.
 *
 * Both fail the same way if undone: a queue one element short, or in the wrong order, still plays —
 * it just plays the wrong video, because `MediaItemsWithStartPosition` names the item to start on
 * by index. Nothing else in the suite would notice.
 *
 * Instrumented because [MediaItem] is an Android class and this project does not use Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackResolveTest {

    private fun video(id: String) = Video(
        youtubeId = id,
        jellyfinItemId = "jf-$id",
        fileName = "$id.mp4",
        title = "Title $id",
        channel = "Sample Channel",
        channelId = null,
        durationSeconds = 100,
        uploadDate = "20260721",
        description = null,
        tags = emptyList(),
        youtubeCategories = emptyList(),
        thumbnailUrl = null,
        played = false,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = 0L,
        metadataSource = METADATA_SOURCE_INDEX,
        metadataUpdatedAt = 0L,
        missedSyncs = 0,
    )

    private fun items(vararg ids: String) =
        ids.map { MediaItem.Builder().setMediaId(it).build() }

    private val settings = Settings(
        serverUrl = "https://example.test",
        apiKey = "",
        accessToken = "token",
        userId = "user",
        userName = "user",
        libraryId = "lib",
        libraryName = "Library",
        indexUrl = "",
        lastSyncAt = 0L,
        lastSyncLibraryId = "lib",
    )

    /** The failure the batch read introduces: a missing row shortening the queue. */
    @Test
    fun anIdWithNoRowStaysInTheQueue() {
        val queue = items("aaa", "missing", "ccc")
        val rows = mapOf("aaa" to video("aaa"), "ccc" to video("ccc"))

        val resolved = resolveQueue(queue, rows, settings)

        assertEquals(3, resolved.size)
        assertEquals(listOf("aaa", "missing", "ccc"), resolved.map { it.mediaId })
        // URI-less on purpose: ExoPlayer raises a source error the player screen can show, which
        // is a visible failure rather than a queue that quietly plays something else.
        assertNull(resolved[1].localConfiguration)
        assertNotNull(resolved[0].localConfiguration)
        assertNotNull(resolved[2].localConfiguration)
    }

    /**
     * The map is deliberately given in an order unrelated to the queue's, since that is what an
     * `IN (...)` query returns. The output must follow the queue, not the map.
     */
    @Test
    fun theQueueKeepsItsOwnOrder() {
        val queue = items("first", "second", "third")
        val rows = linkedMapOf(
            "third" to video("third"),
            "first" to video("first"),
            "second" to video("second"),
        )

        val resolved = resolveQueue(queue, rows, settings)

        assertEquals(listOf("first", "second", "third"), resolved.map { it.mediaId })
    }

    /** A queue of nothing but misses is still a queue of the same length. */
    @Test
    fun everyIdMissingStillYieldsTheSameLength() {
        val queue = items("a", "b")

        val resolved = resolveQueue(queue, emptyMap(), settings)

        assertEquals(2, resolved.size)
        assertEquals(listOf("a", "b"), resolved.map { it.mediaId })
        assertNull(resolved[0].localConfiguration)
        assertNull(resolved[1].localConfiguration)
    }

    /** A duplicate id resolves twice: the map is deduped, the queue is not. */
    @Test
    fun aRepeatedIdResolvesEveryTimeItAppears() {
        val queue = items("same", "other", "same")
        val rows = mapOf("same" to video("same"), "other" to video("other"))

        val resolved = resolveQueue(queue, rows, settings)

        assertEquals(listOf("same", "other", "same"), resolved.map { it.mediaId })
        assertNotNull(resolved[2].localConfiguration)
    }
}
