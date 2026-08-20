package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-scoped playback slot the mini-player bar reads. Everything here is the *contract between
 * the service and the bar*: what makes a bar appear, what makes it go away, and that its buttons
 * reach the player rather than a copy of it.
 */
class NowPlayingStateTest {

    private val state = NowPlayingState()

    private fun playing(id: String = "aaaaaaaaaaa", isPlaying: Boolean = true) =
        NowPlaying(youtubeId = id, title = "Title", artworkUri = "http://art", isPlaying = isPlaying)

    @Test
    fun nothingIsPlayingUntilTheServiceSaysSo() {
        assertNull(state.nowPlaying.value)
    }

    @Test
    fun showPublishesTheItem() {
        state.show(playing())

        assertEquals("aaaaaaaaaaa", state.nowPlaying.value?.youtubeId)
        assertEquals("Title", state.nowPlaying.value?.title)
        assertTrue(state.nowPlaying.value?.isPlaying == true)
    }

    /** An empty queue is what a stop leaves behind, and it is the bar's cue to disappear. */
    @Test
    fun showNullClearsTheBar() {
        state.show(playing())

        state.show(null)

        assertNull(state.nowPlaying.value)
    }

    @Test
    fun setPlayingFlipsTheIconWithoutDisturbingTheRest() {
        state.show(playing(isPlaying = true))

        state.setPlaying(false)

        assertEquals(false, state.nowPlaying.value?.isPlaying)
        assertEquals("aaaaaaaaaaa", state.nowPlaying.value?.youtubeId)
        assertEquals("Title", state.nowPlaying.value?.title)
    }

    /**
     * A play/pause report can outlive the item it describes (the service's listeners both fire on
     * the way out). Resurrecting a cleared bar from one would leave a row pointing at nothing.
     */
    @Test
    fun setPlayingOnAnEmptySlotStaysEmpty() {
        state.setPlaying(true)

        assertNull(state.nowPlaying.value)
    }

    @Test
    fun theButtonsReachTheRegisteredTransport() {
        val calls = mutableListOf<String>()
        state.attach(object : NowPlayingState.Transport {
            override fun playPause() {
                calls += "playPause"
            }
            override fun stop() {
                calls += "stop"
            }
        })

        state.playPause()
        state.stop()

        assertEquals(listOf("playPause", "stop"), calls)
    }

    /** No service, no player to command — a tap on a stale bar must not throw. */
    @Test
    fun theButtonsAreInertWithNoTransport() {
        state.playPause()
        state.stop()
    }

    /**
     * The service going away takes both halves with it: a bar left on screen would command a
     * released player, and its buttons would reach a transport whose player is null.
     */
    @Test
    fun detachClearsTheItemAndTheTransport() {
        val calls = mutableListOf<String>()
        state.attach(object : NowPlayingState.Transport {
            override fun playPause() {
                calls += "playPause"
            }
            override fun stop() {
                calls += "stop"
            }
        })
        state.show(playing())

        state.detach()

        assertNull(state.nowPlaying.value)
        state.playPause()
        state.stop()
        assertEquals(emptyList<String>(), calls)
    }
}
