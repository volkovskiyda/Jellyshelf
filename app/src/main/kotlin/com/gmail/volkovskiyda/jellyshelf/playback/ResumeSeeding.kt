package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.Player
import com.gmail.volkovskiyda.jellyshelf.util.ticksToMillis

/**
 * Where a video the queue has just moved to should start, or null to leave it where the player
 * put it.
 *
 * media3 has no per-item start position: a playlist is set with **one** index and **one**
 * position, so [PlaybackService]'s session callback can only seed the video a queue opens on.
 * Everything the queue reaches afterwards — by auto-advance or by the previous/next buttons —
 * would otherwise begin at zero however far the user had got through it, and the periodic save
 * would then write that zero over the resume point it should have honoured.
 *
 * Only moves *within* an existing queue seed a position:
 * - `PLAYLIST_CHANGED` is a queue being set or cleared, and those paths already carry an explicit
 *   start position — the session callback's resume lookup, or the transcode fallback's "resume
 *   exactly where the failed decode left off". Seeding on top of either would overrule it.
 * - `REPEAT` is the same video going round again, which starts from the beginning by definition.
 *
 * A saved position of zero returns null rather than a seek to zero: a completed video has its
 * position cleared (see `onPlaybackStopped`), and it should play from the start.
 */
internal fun resumeSeekMs(transitionReason: Int, savedTicks: Long): Long? {
    val queueMove = transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    if (!queueMove) return null
    return ticksToMillis(savedTicks).takeIf { it > 0L }
}
