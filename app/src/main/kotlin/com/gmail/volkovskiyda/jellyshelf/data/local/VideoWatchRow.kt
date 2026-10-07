package com.gmail.volkovskiyda.jellyshelf.data.local

/**
 * The watch-state columns of one video, the return type of [VideoDao.observeWatchStates] — the
 * same reasoning as [VideoBrowseRow], narrower: Room re-runs an observed query on every write to
 * `videos`, including the position save every ten seconds while a video plays, so the player's
 * queue panel reads four columns per queued id rather than whole rows.
 */
data class VideoWatchRow(
    val youtubeId: String,
    val played: Boolean,
    val playbackPositionTicks: Long,
    val durationSeconds: Long,
)
