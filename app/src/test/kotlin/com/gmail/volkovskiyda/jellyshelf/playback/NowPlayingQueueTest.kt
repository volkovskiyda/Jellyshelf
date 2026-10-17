package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingQueueTest {

    private val queue = NowPlayingQueue()

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
