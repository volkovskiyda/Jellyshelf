package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The session's whole queue — skipped videos included — beside the player's timeline, which holds
 * only the videos that will actually play.
 *
 * Skipping is done by leaving a video out of the timeline rather than by stepping over it as it
 * comes up, and that one choice is what makes every transport agree for free: auto-advance,
 * Next/Previous on the player screen, the mini-player bar, the media notification and "Skip to
 * the end" all walk media3's timeline, and none of them can reach an item that is not in it. Nor
 * can a skipped video ever start loading, so it is never preloaded, reported or resumed — skipping
 * cannot touch watch state. Stepping over items as they arrived could promise none of that.
 *
 * What the user sees is this class's list, not the timeline: the Now playing panel and the Others
 * tab's filter read it through [NowPlayingQueue.items], so a skipped video keeps its place on
 * screen. The two are kept in step from here: a change to the skip list removes or re-inserts
 * items ([onSkippedChanged]), and a jump to a skipped row inserts it first ([jumpTo]).
 *
 * The timeline is always a subsequence of [items] in the same order; [timelineEdits] relies on it,
 * and [onTimelineChanged] adopts whatever timeline breaks it rather than editing one it does not
 * understand. Main thread only, like the player it edits.
 */
internal class QueueKeeper(
    private val nowPlaying: NowPlayingQueue,
    private val player: () -> Player?,
) {
    private var items: List<MediaItem> = emptyList()
    private var skipped: Set<String> = emptySet()

    /**
     * Starts keeping the timeline in step for the service's lifetime ([scope]): a skip or unskip
     * anywhere — the panel, the Others filter — edits whatever queue is loaded, and a row tap in
     * either Now playing list jumps on the real player, handing [unskip] the video it chose if that
     * one was skipped. One small table behind [skippedIds], so watching it while nothing is queued
     * costs next to nothing.
     */
    fun attach(scope: CoroutineScope, skippedIds: Flow<Set<String>>, unskip: (String) -> Unit) {
        scope.launch { skippedIds.collect(::onSkippedChanged) }
        nowPlaying.attach { youtubeId, play ->
            val wasSkipped = isSkipped(youtubeId)
            if (jumpTo(youtubeId, play) && wasSkipped) unskip(youtubeId)
        }
    }

    /** The items to hand media3 for a new queue, and the index to start on among them. */
    data class Playable(val items: List<MediaItem>, val startIndex: Int)

    /**
     * Takes a new queue — [queue] as the controller sent it, resolved — and returns what the
     * timeline gets: everything not skipped, plus the item at [startIndex] whatever its mark, since
     * that is the video the user asked to play.
     */
    fun load(queue: List<MediaItem>, startIndex: Int, skipped: Set<String>): Playable {
        items = queue
        this.skipped = skipped
        nowPlaying.show(queue)
        val start = queue.getOrNull(startIndex)
        val playable = queue.filterIndexed { index, item -> index == startIndex || item.mediaId !in skipped }
        return Playable(playable, playable.indexOf(start).coerceAtLeast(0))
    }

    /** A queue that is exactly what the timeline holds — nothing in it is passed over. */
    fun replace(queue: List<MediaItem>) {
        items = queue
        nowPlaying.show(queue)
    }

    /** The queue emptied: nothing is playing, so nothing is queued either. */
    fun clear() = replace(emptyList())

    private fun isSkipped(mediaId: String): Boolean = mediaId in skipped

    /**
     * The skip list moved: drop newly skipped items from the timeline and put unskipped ones back
     * where they belong. The playing item stays whatever its mark — removing it is what Next does,
     * and media3 would report it as a playlist change, which skips the resume seed of the video
     * after it.
     */
    fun onSkippedChanged(skipped: Set<String>) {
        this.skipped = skipped
        val p = player() ?: return
        if (p.mediaItemCount == 0 || items.isEmpty()) return
        val target = playableIds(items.map { it.mediaId }, skipped, p.currentMediaItem?.mediaId)
        timelineEdits(p.mediaIds(), target)?.let { p.apply(it) }
    }

    /**
     * Adopts a timeline that is no longer this queue's — a resumption from the system, an item
     * swapped wholesale — as the queue itself. Editing a timeline the queue cannot account for would
     * scramble it; showing it as it is keeps the panel honest.
     */
    fun onTimelineChanged() {
        val p = player() ?: return
        val timeline = p.mediaIds()
        if (timeline.isEmpty() || isInOrderWithin(timeline, items.map { it.mediaId })) return
        replace(List(p.mediaItemCount, p::getMediaItemAt))
    }

    /**
     * Moves playback to [mediaId], inserting it into the timeline first when it was skipped out of
     * it. False when the queue has no such video. Unskipping it is the caller's half: this only
     * makes the jump possible.
     */
    fun jumpTo(mediaId: String, play: Boolean): Boolean {
        val p = player() ?: return false
        var index = p.mediaIds().indexOf(mediaId)
        if (index < 0) {
            val present = p.mediaIds().toSet() + mediaId
            val target = items.map { it.mediaId }.filter { it in present }
            if (mediaId !in target) return false
            timelineEdits(p.mediaIds(), target)?.let { p.apply(it) } ?: return false
            index = p.mediaIds().indexOf(mediaId)
        }
        p.seekToDefaultPosition(index)
        if (play) p.play()
        return true
    }

    private fun Player.apply(edits: List<TimelineEdit>) {
        for (edit in edits) {
            when (edit) {
                is TimelineEdit.Remove -> removeMediaItem(edit.index)
                is TimelineEdit.Insert ->
                    items.firstOrNull { it.mediaId == edit.mediaId }?.let { addMediaItem(edit.index, it) }
            }
        }
    }

    private fun Player.mediaIds(): List<String> = List(mediaItemCount) { getMediaItemAt(it).mediaId }
}

/**
 * What the timeline should hold for [queue]: every video not in [skipped], and [playing] whatever
 * its mark.
 */
internal fun playableIds(queue: List<String>, skipped: Set<String>, playing: String?): List<String> =
    queue.filter { it == playing || it !in skipped }

/** Whether [timeline] is [queue] with some items left out and nothing moved — the keeper's invariant. */
internal fun isInOrderWithin(timeline: List<String>, queue: List<String>): Boolean =
    timelineEdits(timeline, queue)?.none { it is TimelineEdit.Remove } == true

/** One edit to the player's timeline, by index into the timeline as the edits before it left it. */
internal sealed interface TimelineEdit {
    data class Remove(val index: Int) : TimelineEdit
    data class Insert(val index: Int, val mediaId: String) : TimelineEdit
}

/**
 * The removes and inserts that turn [timeline] into [target], in the order they apply: the removes
 * back to front, so no index shifts under the next one, then the inserts front to back, each at the
 * position its id has in [target].
 *
 * Null when what survives the removes is not in [target]'s order — a timeline something other than
 * this queue arranged, which no amount of inserting can line up. An empty list means there is
 * nothing to do.
 */
internal fun timelineEdits(timeline: List<String>, target: List<String>): List<TimelineEdit>? {
    val wanted = target.toSet()
    val edits = mutableListOf<TimelineEdit>()
    val kept = timeline.toMutableList()
    for (index in timeline.indices.reversed()) {
        if (timeline[index] !in wanted) {
            edits += TimelineEdit.Remove(index)
            kept.removeAt(index)
        }
    }
    var next = 0
    target.forEachIndexed { index, id ->
        if (next < kept.size && kept[next] == id) {
            next++
        } else {
            edits += TimelineEdit.Insert(index, id)
        }
    }
    return edits.takeIf { next == kept.size }
}
