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
 * Nor does the timeline hold every video that will play: only a window of them, the playing one
 * and [TIMELINE_RADIUS] on either side ([timelineWindow]), moved along as playback moves
 * ([reconcile]). media3 pays for the whole timeline on the main thread — a source per item when
 * it is set, and a comparison and a copy to every controller each time it changes: ~70 ms as a
 * 1,245-video queue arrived and 20–36 ms at every later change, measured 2026-11-15 on a mid-range
 * API 34 phone. No transport looks further than one item either way, so a window is all any of
 * them needs; the far end of the timeline is the far end of the queue only once playback is within
 * the radius of it, which is exactly when it matters.
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
            val wasSkipped = youtubeId in skipped
            if (jumpTo(youtubeId, play) && wasSkipped) unskip(youtubeId)
        }
    }

    /** The items to hand media3 for a new queue, and the index to start on among them. */
    data class Playable(val items: List<MediaItem>, val startIndex: Int)

    /**
     * Takes a new queue — [queue] as the controller sent it, resolved — and returns what the
     * timeline gets: the window around the item at [startIndex] of everything not skipped, that
     * item included whatever its mark, since it is the video the user asked to play.
     */
    fun load(queue: List<MediaItem>, startIndex: Int, skipped: Set<String>): Playable {
        items = queue
        this.skipped = skipped
        nowPlaying.show(queue)
        val start = queue.getOrNull(startIndex)
        val window = timelineWindow(playableIds(queue.map { it.mediaId }, skipped, start?.mediaId), start?.mediaId)
            .toSet()
        val playable = queue.filter { it.mediaId in window }
        return Playable(playable, playable.indexOf(start).coerceAtLeast(0))
    }

    /** A queue that is exactly what the timeline holds — nothing in it is passed over. */
    fun replace(queue: List<MediaItem>) {
        items = queue
        nowPlaying.show(queue)
    }

    /** The queue emptied: nothing is playing, so nothing is queued either. */
    fun clear() = replace(emptyList())

    /**
     * The skip list moved: drop newly skipped items from the timeline and put unskipped ones back
     * where they belong. The playing item stays whatever its mark — removing it is what Next does,
     * and media3 would report it as a playlist change, which skips the resume seed of the video
     * after it.
     */
    fun onSkippedChanged(skipped: Set<String>) {
        this.skipped = skipped
        reconcile()
    }

    /**
     * Edits the timeline into the window around the playing video. For the service to call when
     * playback moves to another video: what fell out behind is dropped and what came into reach
     * ahead is added (or the other way round, going back). Those edits are at the window's far ends,
     * [TIMELINE_RADIUS] items from the playing one, so the next video — already preloaded — is
     * never touched.
     *
     * Left alone when the playing video is not in the queue — a timeline about to be adopted or
     * replaced — since the window would then be around nothing, and the edits would remove the
     * very video that is playing.
     */
    fun reconcile() {
        val p = player() ?: return
        if (p.mediaItemCount == 0 || items.isEmpty()) return
        val playing = p.currentMediaItem?.mediaId
        val target = timelineWindow(playableIds(items.map { it.mediaId }, skipped, playing), playing)
        if (playing !in target) return
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
     * Moves playback to [mediaId], inserting it into the timeline first when it is not there —
     * skipped out of it, or outside the window — and moving the window to it afterwards. False when
     * the queue has no such video. Unskipping it is the caller's half: this only makes the jump
     * possible.
     */
    fun jumpTo(mediaId: String, play: Boolean): Boolean {
        val p = player() ?: return false
        // Already there: a seek would restart it, which nobody tapping the playing row asked for.
        if (p.currentMediaItem?.mediaId == mediaId) {
            if (play) p.play()
            return true
        }
        var index = p.mediaIds().indexOf(mediaId)
        if (index < 0) {
            val present = p.mediaIds().toSet() + mediaId
            val target = items.map { it.mediaId }.filter { it in present }
            if (mediaId !in target) return false
            timelineEdits(p.mediaIds(), target)?.let { p.apply(it) } ?: return false
            index = p.mediaIds().indexOf(mediaId)
        }
        p.seekToDefaultPosition(index)
        // The service's transition listener does this too; here as well, so the window never
        // depends on who is listening.
        reconcile()
        if (play) p.play()
        return true
    }

    /**
     * One player call per run of neighbouring edits rather than one per item: each call is a
     * timeline change of its own, sent to every controller, and a jump across the queue moves the
     * whole window.
     */
    private fun Player.apply(edits: List<TimelineEdit>) {
        val byId by lazy(LazyThreadSafetyMode.NONE) { items.associateBy { it.mediaId } }
        for (run in editRuns(edits)) {
            when (val first = run.first()) {
                is TimelineEdit.Remove -> removeMediaItems(run.last().index, first.index + 1)
                is TimelineEdit.Insert ->
                    addMediaItems(first.index, run.mapNotNull { byId[(it as TimelineEdit.Insert).mediaId] })
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

/**
 * How many videos the timeline holds on either side of the playing one — see [QueueKeeper]. Far
 * more than any transport reaches (one), so that a run of quick Next taps never outruns the window.
 */
internal const val TIMELINE_RADIUS = 20

/**
 * The part of [playable] the timeline holds: [playing] and up to [radius] videos on either side of
 * it, in order. The first videos of the queue when [playing] is not among them.
 */
internal fun timelineWindow(playable: List<String>, playing: String?, radius: Int = TIMELINE_RADIUS): List<String> {
    val center = playable.indexOf(playing).coerceAtLeast(0)
    return playable.subList((center - radius).coerceAtLeast(0), (center + radius + 1).coerceAtMost(playable.size))
}

/** Whether [timeline] is [queue] with some items left out and nothing moved — the keeper's invariant. */
internal fun isInOrderWithin(timeline: List<String>, queue: List<String>): Boolean =
    timelineEdits(timeline, queue)?.none { it is TimelineEdit.Remove } == true

/** One edit to the player's timeline, by index into the timeline as the edits before it left it. */
internal sealed interface TimelineEdit {
    val index: Int

    data class Remove(override val index: Int) : TimelineEdit
    data class Insert(override val index: Int, val mediaId: String) : TimelineEdit
}

/**
 * [edits] as [timelineEdits] orders them, cut into runs that are each one range: removes of
 * neighbouring indices going down, inserts at neighbouring indices going up.
 */
internal fun editRuns(edits: List<TimelineEdit>): List<List<TimelineEdit>> {
    val runs = mutableListOf<MutableList<TimelineEdit>>()
    for (edit in edits) {
        val last = runs.lastOrNull()?.last()
        val continues = when (edit) {
            is TimelineEdit.Remove -> last is TimelineEdit.Remove && edit.index == last.index - 1
            is TimelineEdit.Insert -> last is TimelineEdit.Insert && edit.index == last.index + 1
        }
        if (continues) runs.last() += edit else runs += mutableListOf(edit)
    }
    return runs
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
