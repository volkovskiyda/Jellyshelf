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

/** Matched a metadata API entry only — the bot server that downloaded the video. */
const val METADATA_SOURCE_API = "API"

/** Matched both the metadata API and the index; the fields were coalesced newest-first. */
const val METADATA_SOURCE_API_INDEX = "API_INDEX"

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
    /**
     * Structured yt-dlp chapters — the *fallback* source: description-parsed timecodes win when
     * both exist (the player applies the priority).
     */
    val chapters: List<Chapter> = emptyList(),
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
    /** Consecutive syncs whose server listing didn't contain this video. Reset to 0 when seen. */
    val missedSyncs: Int,
    /** Why the last in-app yt-dlp metadata fetch failed, or null if the last one succeeded. */
    val lastFetchError: String? = null,
    /** When [lastFetchError] was recorded, epoch millis; 0 when there is no error. */
    val lastFetchErrorAt: Long = 0L,
) {
    /**
     * The last sync (or more) didn't find this video on the server. It is kept for a few syncs
     * before being deleted — a Jellyfin rescan or a paging hiccup can drop a video from one
     * listing and bring it back on the next — so this is "probably gone", not "certainly gone",
     * and is surfaced rather than acted on: playback stays enabled, and removal stays the
     * user's call.
     */
    val missingFromServer: Boolean get() = missedSyncs > 0
}
