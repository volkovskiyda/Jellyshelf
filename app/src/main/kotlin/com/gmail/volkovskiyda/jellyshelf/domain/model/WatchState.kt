package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * How far one video has been watched: the slice of [Video] a progress bar and a watched tick need,
 * for screens that show those for videos they otherwise only know by id — the player's queue
 * panel, whose rows come from the session rather than from Room.
 */
data class WatchState(
    val played: Boolean,
    val playbackPositionTicks: Long,
    val durationSeconds: Long,
)
