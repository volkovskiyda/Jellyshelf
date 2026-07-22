package com.gmail.volkovskiyda.jellyshelf.domain.model

// Where a video's YouTube metadata came from. Ordered loosely by richness; used to drive the
// "Get / Update metadata" action, the source badge, and the Uncategorized filter. These string
// values are persisted in the database, so they must never change.
/** Only Jellyfin item fields — no jellyshelf-index.json or in-app yt-dlp match. "Uncategorized". */
const val METADATA_SOURCE_JELLYFIN = "JELLYFIN"

/** Matched a jellyshelf-index.json entry (the external build-library-index.sh script). */
const val METADATA_SOURCE_INDEX = "INDEX"

/** Fetched in-app by the bundled yt-dlp (youtubedl-android). */
const val METADATA_SOURCE_YTDLP = "YTDLP"

/**
 * A library video as the app's UI and ViewModels see it — the domain model that repository
 * interfaces expose. The data layer maps its Room row (`VideoEntity`) onto this, so nothing above
 * the data layer depends on Room.
 */
data class Video(
    val youtubeId: String,
    val jellyfinItemId: String?,
    val fileName: String,
    val title: String,
    val channel: String?,
    val channelId: String?,
    val durationSeconds: Long,
    val uploadDate: String?,
    val description: String?,
    val tags: List<String>,
    val youtubeCategories: List<String>,
    val thumbnailUrl: String?,
    val played: Boolean,
    val playbackPositionTicks: Long,
    val playCount: Int,
    val lastSyncedAt: Long,
    /** One of METADATA_SOURCE_*: where the YouTube metadata above came from. */
    val metadataSource: String,
    /** When that metadata was produced by its source, epoch millis. */
    val metadataUpdatedAt: Long,
)
