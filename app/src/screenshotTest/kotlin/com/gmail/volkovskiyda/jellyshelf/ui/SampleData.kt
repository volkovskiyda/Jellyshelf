package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video

/**
 * Fixtures shared by every preview, so a golden only ever changes because the UI changed.
 *
 * Everything here is deliberately fixed — no clocks, no random ids, no network-backed thumbnails
 * (`thumbnailUrl = null`, and previews pass `thumbnailModel = null`): a reference image that
 * depends on the machine or the moment it was generated is worse than no reference image.
 */
internal val sampleVideo = Video(
    youtubeId = "1ubm7Q6DL-I",
    jellyfinItemId = "420a0d37859837117add1115b61a7368",
    fileName = "20260721_101507 - Sample video [1ubm7Q6DL-I].mp4",
    title = "A reasonably long video title that wraps onto a second line",
    channel = "Sample Channel",
    channelId = "UC0000000000000000000000",
    durationSeconds = 754,
    uploadDate = "20260721",
    description = "A sample description, long enough to show how the detail screen lays out a " +
        "paragraph of body text under the actions.",
    tags = listOf("sample", "preview"),
    youtubeCategories = listOf("Entertainment"),
    thumbnailUrl = null,
    played = false,
    playbackPositionTicks = 0L,
    playCount = 0,
    lastSyncedAt = 0L,
    metadataSource = METADATA_SOURCE_INDEX,
    metadataUpdatedAt = 0L,
    missedSyncs = 0,
)

/** Watched: shows the check icon, and no progress bar even if a position lingers. */
internal val watchedVideo = sampleVideo.copy(played = true, playbackPositionTicks = 3_000_000_000L)

/** Part-watched: 40% of 754s as ticks (100ns units) — drives the thumbnail progress bar. */
internal val partWatchedVideo = sampleVideo.copy(playbackPositionTicks = 3_016_000_000L)

/** The server's listing has stopped including it — see [Video.missingFromServer]. */
internal val missingVideo = sampleVideo.copy(missedSyncs = 1)
