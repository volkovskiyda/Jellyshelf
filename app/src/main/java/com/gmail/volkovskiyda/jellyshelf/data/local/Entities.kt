package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val CATEGORY_TYPE_AUTO_CHANNEL = "AUTO_CHANNEL"
const val CATEGORY_TYPE_AUTO_YEAR = "AUTO_YEAR"
const val CATEGORY_TYPE_AUTO_MONTH = "AUTO_MONTH"
const val CATEGORY_TYPE_AUTO_DURATION = "AUTO_DURATION"
const val CATEGORY_TYPE_AUTO_YT_CATEGORY = "AUTO_YT_CATEGORY"
const val CATEGORY_TYPE_MANUAL = "MANUAL"

/** Synthetic type for the "Others" tab's virtual filters (see [VIRTUAL_CATEGORY_UNCATEGORIZED] etc.). */
const val CATEGORY_TYPE_OTHERS = "OTHERS"

// Where a video's YouTube metadata came from. Ordered loosely by richness; used to drive the
// "Get / Update metadata" action, the source badge, and the Uncategorized filter.
/** Only Jellyfin item fields — no jellyshelf-index.json or in-app yt-dlp match. "Uncategorized". */
const val METADATA_SOURCE_JELLYFIN = "JELLYFIN"
/** Matched a jellyshelf-index.json entry (the external build-library-index.sh script). */
const val METADATA_SOURCE_INDEX = "INDEX"
/** Fetched in-app by the bundled yt-dlp (youtubedl-android). */
const val METADATA_SOURCE_YTDLP = "YTDLP"

// Virtual category ids for the "Others" tab. These are not stored rows — the repository routes
// them to live queries over the videos table, so they always reflect current state.
const val VIRTUAL_CATEGORY_UNCATEGORIZED = "virtual:uncategorized"
const val VIRTUAL_CATEGORY_WATCHED = "virtual:watched"
const val VIRTUAL_CATEGORY_UNWATCHED = "virtual:unwatched"
const val VIRTUAL_CATEGORY_CONTINUE = "virtual:continue"

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val youtubeId: String,
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
    val metadataSource: String = METADATA_SOURCE_JELLYFIN,
    /**
     * When that metadata was produced by its source, in epoch millis. For yt-dlp this is the device
     * clock at fetch; for the index it is the entry's yt-dlp `epoch`. Drives newest-wins on sync.
     */
    val metadataUpdatedAt: Long = 0L,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val createdAt: Long,
)

@Entity(
    tableName = "video_category",
    primaryKeys = ["youtubeId", "categoryId"],
    indices = [Index("categoryId")],
)
data class VideoCategoryCrossRef(
    val youtubeId: String,
    val categoryId: String,
)

/** A category row plus its member count, for the categories list. */
data class CategoryWithCount(
    @Embedded val category: CategoryEntity,
    val videoCount: Int,
)
