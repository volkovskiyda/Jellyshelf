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
            override fun next() {
                calls += "next"
            }
            override fun stop() {
                calls += "stop"
            }
        })

        state.playPause()
        state.next()
        state.stop()

        assertEquals(listOf("playPause", "next", "stop"), calls)
    }

    /** No service, no player to command — a tap on a stale bar must not throw. */
    @Test
    fun theButtonsAreInertWithNoTransport() {
        state.playPause()
        state.next()
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
            override fun next() {
                calls += "next"
            }
            override fun stop() {
                calls += "stop"
            }
        })
        state.show(playing())

        state.detach()

        assertNull(state.nowPlaying.value)
        state.playPause()
        state.next()
        state.stop()
        assertEquals(emptyList<String>(), calls)
    }

    /** The same rule [setPlaying] follows: a late timeline report must not resurrect a cleared bar. */
    @Test
    fun setHasNextOnAnEmptySlotStaysEmpty() {
        state.setHasNext(true)

        assertNull(state.nowPlaying.value)
    }

    /**
     * The queue arrives after the first item does, so the field the bar's next button reads is
     * normally set by a timeline report rather than by the [show] that raised the bar.
     */
    @Test
    fun setHasNextUpdatesTheShowingItem() {
        state.show(playing())

        state.setHasNext(true)

        assertEquals(true, state.nowPlaying.value?.hasNext)
    }

    /**
     * The whole point of [NowPlaying.progress] returning null: a zero duration is "not known yet",
     * and a bar drawing it as 0f would claim the video is at its start.
     */
    @Test
    fun progressIsNullUntilTheDurationIsKnown() {
        state.show(playing())
        state.setProgress(positionMs = 30_000L, durationMs = 0L)

        assertNull(state.nowPlaying.value?.progress)
    }

    @Test
    fun progressIsThePositionOverTheDuration() {
        state.show(playing())
        state.setProgress(positionMs = 30_000L, durationMs = 120_000L)

        assertEquals(0.25f, state.nowPlaying.value?.progress)
    }

    /**
     * A position past the duration is not hypothetical: the player reports position and duration
     * independently, and a report taken across the end of a video can arrive with the two out of
     * step. Clamped, because a progress bar handed 1.4f draws past its own track.
     */
    @Test
    fun progressPastTheEndIsClamped() {
        state.show(playing())
        state.setProgress(positionMs = 130_000L, durationMs = 120_000L)

        assertEquals(1f, state.nowPlaying.value?.progress)
    }

    @Test
    fun progressIsIgnoredWhenNothingIsPlaying() {
        state.setProgress(positionMs = 30_000L, durationMs = 120_000L)

        assertNull(state.nowPlaying.value)
    }

    /**
     * A queue advance publishes a fresh item through [NowPlayingState.show], and the fresh item
     * must not inherit the outgoing one's position: the service's [show] call carries no
     * progress, so the line disappears until the new video's first tick rather than standing at
     * the old video's fraction — which would be a confident claim about a video it never
     * measured, for up to a whole tick. Pinned because the tempting refactor is a `copy` that
     * preserves "unrelated" fields, and progress is exactly the field that must not survive.
     */
    @Test
    fun aQueueAdvanceDoesNotInheritTheOldItemsProgress() {
        state.show(playing(id = "aaaaaaaaaaa"))
        state.setProgress(positionMs = 60_000L, durationMs = 120_000L)

        state.show(playing(id = "bbbbbbbbbbb"))

        assertNull(state.nowPlaying.value?.progress)
    }
}
