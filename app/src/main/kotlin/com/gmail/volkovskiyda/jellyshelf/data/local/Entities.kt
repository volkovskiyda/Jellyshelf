package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN

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
    /**
     * How many consecutive syncs have failed to see this video on the server. Reset to 0 whenever
     * a sync does see it; the row is only deleted once the counter reaches the grace limit, so a
     * server that is mid-rescan can't take user-authored data with it.
     */
    val missedSyncs: Int = 0,
    /**
     * Why the last in-app yt-dlp fetch for this video failed, or null if the last one succeeded
     * (or none has run). Kept so a video that never gets metadata can say why instead of just
     * sitting in Uncategorized forever — the sync auto-fill retries it every sync, and without
     * this the repeated failure is invisible.
     */
    val lastFetchError: String? = null,
    /** When [lastFetchError] was recorded, epoch millis; 0 when there is no error. */
    val lastFetchErrorAt: Long = 0L,
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

/**
 * A category row with its member count and whether any member video's description matches the
 * current search query — the raw material for [com.gmail.volkovskiyda.jellyshelf.data.repository.SearchRanking].
 * The name is scored in Kotlin (which needs the whole tier ladder), but a member-description hit
 * can only ever be a "contains", so it is precomputed once in SQL as this boolean.
 */
data class RankedCategory(
    @Embedded val category: CategoryEntity,
    val videoCount: Int,
    val descriptionMatch: Boolean,
)
