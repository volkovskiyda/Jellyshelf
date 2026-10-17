package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [QueueKeeper] editing a real ExoPlayer's timeline: what [QueueKeeperTest] computes, applied the
 * way media3 applies it. Never prepared — nothing here loads a stream; the timeline alone is the
 * subject, and it is what every transport (Next, auto-advance, the notification) walks.
 */
@RunWith(AndroidJUnit4::class)
class QueueKeeperInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer
    private lateinit var nowPlaying: NowPlayingQueue
    private lateinit var keeper: QueueKeeper

    private val queue = listOf("a", "b", "c", "d").map(::item)

    @Before
    fun setUp() = onMain {
        player = ExoPlayer.Builder(instrumentation.targetContext).build()
        nowPlaying = NowPlayingQueue()
        keeper = QueueKeeper(nowPlaying) { player }
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                keeper.onTimelineChanged()
            }
        })
    }

    @After
    fun tearDown() = onMain { player.release() }

    @Test
    fun aQueueLoadsWithItsSkippedVideosLeftOut() = onMain {
        start(startIndex = 0, skipped = setOf("c"))

        assertEquals(listOf("a", "b", "d"), timeline())
        assertEquals(listOf("a", "b", "c", "d"), nowPlaying.items.value.map { it.mediaId })
    }

    @Test
    fun skippingAndUnskipping_editTheTimelineInPlace() = onMain {
        start(startIndex = 1, skipped = emptySet())

        keeper.onSkippedChanged(setOf("a", "c"))
        assertEquals(listOf("b", "d"), timeline())
        assertEquals("b", player.currentMediaItem?.mediaId)
        // Nothing after the playing video but "d": Next still has somewhere to go.
        assertTrue(player.hasNextMediaItem())

        keeper.onSkippedChanged(setOf("a", "c", "d"))
        assertFalse(player.hasNextMediaItem())

        keeper.onSkippedChanged(emptySet())
        assertEquals(listOf("a", "b", "c", "d"), timeline())
        assertEquals("b", player.currentMediaItem?.mediaId)
        // The screens' list never moved through any of it.
        assertEquals(listOf("a", "b", "c", "d"), nowPlaying.items.value.map { it.mediaId })
    }

    @Test
    fun thePlayingVideo_staysWhenItIsSkipped() = onMain {
        start(startIndex = 2, skipped = emptySet())

        keeper.onSkippedChanged(setOf("c"))

        assertEquals(listOf("a", "b", "c", "d"), timeline())
        assertEquals("c", player.currentMediaItem?.mediaId)
    }

    @Test
    fun jumpingToASkippedVideo_putsItBackAndPlaysIt() = onMain {
        start(startIndex = 0, skipped = setOf("c"))

        assertTrue(keeper.jumpTo("c", play = false))

        assertEquals(listOf("a", "b", "c", "d"), timeline())
        assertEquals("c", player.currentMediaItem?.mediaId)
        // The skip list itself has not moved yet — the caller unskips — and the next reconcile
        // keeps the playing video where it is.
        keeper.onSkippedChanged(setOf("c"))
        assertEquals("c", player.currentMediaItem?.mediaId)
    }

    /** A tap on the playing row — in the panel or the Others filter — must not restart it. */
    @Test
    fun jumpingToThePlayingVideo_leavesItWhereItIs() = onMain {
        start(startIndex = 1, skipped = emptySet())
        player.seekTo(5_000L)

        assertTrue(keeper.jumpTo("b", play = false))

        assertEquals("b", player.currentMediaItem?.mediaId)
        assertEquals(5_000L, player.currentPosition)
    }

    @Test
    fun jumpingToAVideoNotInTheQueue_doesNothing() = onMain {
        start(startIndex = 0, skipped = emptySet())

        assertFalse(keeper.jumpTo("zzz", play = false))
        assertEquals("a", player.currentMediaItem?.mediaId)
    }

    /** The transcode fallback and a system resumption replace the timeline wholesale. */
    @Test
    fun aTimelineReplacedFromElsewhere_becomesTheQueue() = onMain {
        start(startIndex = 0, skipped = setOf("c"))

        player.setMediaItem(item("z"))

        assertEquals(listOf("z"), nowPlaying.items.value.map { it.mediaId })
    }

    private fun start(startIndex: Int, skipped: Set<String>) {
        val playable = keeper.load(queue, startIndex, skipped)
        player.setMediaItems(playable.items, playable.startIndex, 0L)
    }

    private fun timeline(): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    // An unreachable asset: ExoPlayer builds a source per item as it is added, which needs a URI,
    // but nothing is ever prepared, so nothing is ever read from it.
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("asset:///none/$id.mp4").build()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
