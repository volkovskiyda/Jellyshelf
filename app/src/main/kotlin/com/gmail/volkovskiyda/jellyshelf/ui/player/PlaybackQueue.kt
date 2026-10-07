package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter

/** What the session is handed: the ids to queue, and which one to start on. */
internal data class PlaybackQueue(val ids: List<String>, val startIndex: Int)

/**
 * The queue for playing [youtubeId] out of the list it was opened from.
 *
 * Falls back to just that video whenever [originIds] cannot position it — no origin at all (the
 * media-notification path, a video opened from Detail), or a snapshot taken before the video was
 * added. The fallback is not an error case: a single-item queue is exactly the behavior the player
 * had before queues existed, down to both transport buttons being disabled.
 */
internal fun playbackQueue(originIds: List<String>, youtubeId: String): PlaybackQueue {
    val start = originIds.indexOf(youtubeId)
    return if (start < 0) {
        PlaybackQueue(listOf(youtubeId), 0)
    } else {
        PlaybackQueue(originIds, start)
    }
}

/**
 * One row of the player's queue panel: what the session attached to the queued item.
 *
 * [durationMs] is the stored length, null when it is unknown — a row whose metadata was never
 * fetched shows no duration rather than a misleading `0:00`.
 */
internal data class QueueEntry(
    val mediaId: String,
    val title: String?,
    val channel: String?,
    val artworkUri: String?,
    val durationMs: Long? = null,
)

/**
 * The queue panel's rows, read off the session's items in queue order.
 *
 * Takes the count and an accessor rather than media3-ui-compose's `PlaylistState`, so it stays a
 * plain function the JVM tests can call. Every field is nullable because the service hands back an
 * item with no metadata at all when a queued id no longer resolves to a video — a row with a
 * blank title, not a crash.
 */
internal fun queueEntries(count: Int, itemAt: (Int) -> MediaItem): List<QueueEntry> =
    List(count) { index ->
        val item = itemAt(index)
        val metadata = item.mediaMetadata
        QueueEntry(
            mediaId = item.mediaId,
            title = metadata.title?.toString(),
            channel = metadata.artist?.toString(),
            artworkUri = metadata.artworkUri?.toString(),
            durationMs = metadata.durationMs?.takeIf { it > 0 },
        )
    }

/**
 * Whether a playback-state change means the player screen is done: the last video in the queue
 * reached its end, whether it ran out or the chapter row's next arrow skipped it there.
 *
 * A queue with a video after it never reports [Player.STATE_ENDED] — media3 advances instead — so
 * the state alone already means "nothing follows". The item count is the other half: clearing the
 * queue, which every stopping exit does, also lands the player in `STATE_ENDED`, and that end must
 * not send a screen that is already leaving off to a Detail page as well.
 */
internal fun endsThePlayer(playbackState: Int, mediaItemCount: Int): Boolean =
    playbackState == Player.STATE_ENDED && mediaItemCount > 0

/** What the chapter row's "Skip to the end" does once it has put the video at its end. */
internal enum class SkipToEnd {
    /** Playing: media3 reaches the end on its own — the queue advances, or the player leaves. */
    PlayOut,

    /** Paused with a video after this one: open it, still paused. */
    NextVideo,

    /** Paused with nothing after it: finish this video, which leaves to its details. */
    Finish,
}

/**
 * Which [SkipToEnd] a tap means. Only a playing player reaches the end by itself: a paused one sits
 * on the final frame without ever reporting `STATE_ENDED` (measured on an API 36 emulator and on the
 * Pixel 5, 2026-10-06), so the tap looked like it did nothing. Paused, the skip therefore carries the
 * move itself — and keeps the pause when it opens the next video, as the screen's transport does.
 */
internal fun skipToEnd(playWhenReady: Boolean, hasNext: Boolean): SkipToEnd = when {
    playWhenReady -> SkipToEnd.PlayOut
    hasNext -> SkipToEnd.NextVideo
    else -> SkipToEnd.Finish
}

/**
 * Where the previous-chapter button goes: back to the start of the current chapter when more
 * than [CHAPTER_RESTART_THRESHOLD_MS] into it, otherwise to the start of the one before.
 *
 * That is the "restart vs go back" rule every music player's previous-track button follows, and
 * it is the part of chapter stepping users actually notice — without it, pressing previous
 * halfway through a chapter jumps past material they were watching.
 *
 * Null when there is nowhere to go: no chapters, or already at the start of the first one.
 */
internal fun previousChapterStartMs(chapters: List<Chapter>, positionMs: Long): Long? {
    val index = chapters.indexOfLast { it.startMs <= positionMs }
    if (index < 0) return null
    val current = chapters[index]
    if (positionMs - current.startMs > CHAPTER_RESTART_THRESHOLD_MS) return current.startMs
    return chapters.getOrNull(index - 1)?.startMs
}

/** The start of the first chapter after [positionMs], or null when this is the last one. */
internal fun nextChapterStartMs(chapters: List<Chapter>, positionMs: Long): Long? =
    chapters.firstOrNull { it.startMs > positionMs }?.startMs

/** Past this far into a chapter, previous restarts it instead of stepping back. */
private const val CHAPTER_RESTART_THRESHOLD_MS = 3_000L
