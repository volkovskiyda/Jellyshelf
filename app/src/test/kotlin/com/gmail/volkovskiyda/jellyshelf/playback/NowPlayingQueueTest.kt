package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingQueueTest {

    private val queue = NowPlayingQueue()

    private val items = listOf("a", "b", "c", "d").map { MediaItem.Builder().setMediaId(it).build() }

    /** What has already played is the panel's business; the filter lists what is still ahead. */
    @Test
    fun upcomingStartsAtThePlayingVideo() {
        assertEquals(listOf("c", "d"), upcoming(items, playingId = "c").map { it.mediaId })
        assertEquals(listOf("a", "b", "c", "d"), upcoming(items, playingId = "a").map { it.mediaId })
    }

    @Test
    fun upcomingKeepsTheWholeQueueBeforeAnythingIsPlaying() {
        assertEquals(4, upcoming(items, playingId = null).size)
        assertEquals(0, upcoming(emptyList(), playingId = "a").size)
    }

    @Test
    fun aJumpReachesTheRegisteredService() {
        val jumps = mutableListOf<String>()
        queue.attach { id, play -> jumps += "$id $play" }

        queue.jumpTo("bbbbbbbbbbb", play = true)

        assertEquals(listOf("bbbbbbbbbbb true"), jumps)
    }

    /** No service, no player: a tap on a stale row must not throw. */
    @Test
    fun aJumpWithNoServiceIsInert() {
        queue.jumpTo("bbbbbbbbbbb", play = true)
    }

    /** A filter left on screen must not list a queue nothing is playing any more. */
    @Test
    fun detachTakesTheQueueAndTheJumpWithIt() {
        val jumps = mutableListOf<String>()
        queue.attach { id, _ -> jumps += id }
        queue.show(listOf(MediaItem.Builder().setMediaId("aaaaaaaaaaa").build()))

        queue.detach()
        queue.jumpTo("aaaaaaaaaaa", play = false)

        assertEquals(emptyList<MediaItem>(), queue.items.value)
        assertEquals(emptyList<String>(), jumps)
    }
}
