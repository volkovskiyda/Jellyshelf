package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules behind skipping: which videos the timeline holds, and the edits that get it there
 * from where it is. [QueueKeeperInstrumentedTest] applies the same edits to a real player.
 */
class QueueKeeperTest {

    private val queue = listOf("a", "b", "c", "d", "e")

    @Test
    fun `skipped videos leave the timeline, the rest keep their order`() {
        assertEquals(listOf("a", "c", "e"), playableIds(queue, skipped = setOf("b", "d"), playing = "a"))
    }

    @Test
    fun `the playing video stays whatever its mark`() {
        assertEquals(listOf("a", "b", "e"), playableIds(queue, skipped = setOf("b", "c", "d"), playing = "b"))
    }

    /** The edge case: every video after the playing one skipped leaves it alone. */
    @Test
    fun `a queue whose every later video is skipped plays only the one`() {
        assertEquals(listOf("c"), playableIds(queue, skipped = setOf("a", "b", "d", "e"), playing = "c"))
    }

    @Test
    fun `a skip removes from the back so no index shifts under the next one`() {
        assertEquals(
            listOf(TimelineEdit.Remove(3), TimelineEdit.Remove(1)),
            timelineEdits(timeline = queue, target = listOf("a", "c", "e")),
        )
    }

    @Test
    fun `an unskip goes back to its place in the queue`() {
        assertEquals(
            listOf(TimelineEdit.Insert(1, "b"), TimelineEdit.Insert(3, "d")),
            timelineEdits(timeline = listOf("a", "c", "e"), target = queue),
        )
    }

    @Test
    fun `removes and inserts together land on the target`() {
        val timeline = listOf("a", "c", "e")
        val target = listOf("b", "c", "d")

        val edits = timelineEdits(timeline, target)!!

        assertEquals(target, applied(timeline, edits))
        assertTrue(edits.take(2).all { it is TimelineEdit.Remove })
    }

    @Test
    fun `a timeline already on target needs no edits`() {
        assertEquals(emptyList<TimelineEdit>(), timelineEdits(listOf("a", "c"), listOf("a", "c")))
    }

    /** A timeline something else arranged is left alone rather than scrambled further. */
    @Test
    fun `a timeline out of the queue's order gets no edits at all`() {
        assertNull(timelineEdits(timeline = listOf("c", "a"), target = listOf("a", "c")))
    }

    @Test
    fun `a timeline is the keeper's when it is the queue with items left out`() {
        assertTrue(isInOrderWithin(listOf("a", "d"), queue))
        assertTrue(isInOrderWithin(emptyList(), queue))
        assertFalse(isInOrderWithin(listOf("d", "a"), queue))
        assertFalse(isInOrderWithin(listOf("a", "z"), queue))
    }

    @Test
    fun `a new queue hands media3 the unskipped videos and starts on the chosen one`() {
        val nowPlaying = NowPlayingQueue()
        val keeper = QueueKeeper(nowPlaying) { null }

        val playable = keeper.load(queue.map(::item), startIndex = 3, skipped = setOf("b", "d", "e"))

        assertEquals(listOf("a", "c", "d"), playable.items.map { it.mediaId })
        assertEquals(2, playable.startIndex)
        // The screens list the whole of it, skipped videos included.
        assertEquals(queue, nowPlaying.items.value.map { it.mediaId })
    }

    @Test
    fun `the timeline holds the playing video and the radius on either side`() {
        val long = List(100) { "v$it" }

        assertEquals(long.subList(30, 71), timelineWindow(long, playing = "v50"))
        assertEquals(listOf("b", "c", "d"), timelineWindow(queue, playing = "c", radius = 1))
    }

    @Test
    fun `the window stops at the ends of the queue`() {
        val long = List(100) { "v$it" }

        assertEquals(long.subList(0, 24), timelineWindow(long, playing = "v3"))
        assertEquals(long.subList(78, 100), timelineWindow(long, playing = "v98"))
        // A queue shorter than the window is all of it.
        assertEquals(queue, timelineWindow(queue, playing = "c"))
    }

    @Test
    fun `a window around nothing is the start of the queue`() {
        assertEquals(listOf("a", "b"), timelineWindow(queue, playing = null, radius = 1))
        assertEquals(emptyList<String>(), timelineWindow(emptyList(), playing = "a"))
    }

    @Test
    fun `a long queue loads as the window around the chosen video`() {
        val long = List(100) { "v$it" }
        val keeper = QueueKeeper(NowPlayingQueue()) { null }

        // v49 is skipped, so the window reaches one further back to make up its 20.
        val playable = keeper.load(long.map(::item), startIndex = 50, skipped = setOf("v49", "v90"))

        assertEquals(long.subList(29, 71) - "v49", playable.items.map { it.mediaId })
        assertEquals("v50", playable.items[playable.startIndex].mediaId)
    }

    /** One step forward: one range dropped behind, one added ahead — two player calls, not 2 × n. */
    @Test
    fun `neighbouring edits are one run each`() {
        val long = List(100) { "v$it" }
        val edits = timelineEdits(timelineWindow(long, "v10"), timelineWindow(long, "v60"))!!

        val runs = editRuns(edits)

        assertEquals(2, runs.size)
        assertEquals(List(31) { TimelineEdit.Remove(30 - it) }, runs[0])
        assertEquals(List(41) { TimelineEdit.Insert(it, "v${40 + it}") }, runs[1])
        assertEquals(emptyList<List<TimelineEdit>>(), editRuns(emptyList()))
    }

    @Test
    fun `edits apart from each other are runs of their own`() {
        assertEquals(
            listOf(
                listOf(TimelineEdit.Remove(3)),
                listOf(TimelineEdit.Remove(0)),
                listOf(TimelineEdit.Insert(0, "b")),
                listOf(TimelineEdit.Insert(2, "d")),
            ),
            editRuns(timelineEdits(timeline = listOf("a", "c", "e", "x"), target = listOf("b", "c", "d", "e"))!!),
        )
    }

    @Test
    fun `clearing the queue empties what the screens list`() {
        val nowPlaying = NowPlayingQueue()
        val keeper = QueueKeeper(nowPlaying) { null }
        keeper.load(queue.map(::item), startIndex = 0, skipped = emptySet())

        keeper.clear()

        assertEquals(emptyList<MediaItem>(), nowPlaying.items.value)
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun applied(timeline: List<String>, edits: List<TimelineEdit>): List<String> {
        val list = timeline.toMutableList()
        for (edit in edits) {
            when (edit) {
                is TimelineEdit.Remove -> list.removeAt(edit.index)
                is TimelineEdit.Insert -> list.add(edit.index, edit.mediaId)
            }
        }
        return list
    }
}
