package com.gmail.volkovskiyda.jellyshelf.ui.player

import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter

/** What the session is handed: the ids to queue, and which one to start on. */
internal data class PlaybackQueue(val ids: List<String>, val startIndex: Int)

/**
 * The queue for playing [youtubeId] out of the list it was opened from.
 *
 * Falls back to just that video whenever [originIds] cannot position it — no origin at all (the
 * media-notification path), or a snapshot taken before the video was added. The fallback is not
 * an error case: a single-item queue is exactly the behavior the player had before queues
 * existed, down to both transport buttons being disabled.
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
