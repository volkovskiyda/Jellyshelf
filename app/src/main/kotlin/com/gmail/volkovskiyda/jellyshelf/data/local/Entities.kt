package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN

/**
 * Composites over single columns wherever a query filters *and* sorts, so SQLite can seek the
 * matching range and then read it already in `fileName` order instead of scanning the table and
 * sorting the result. That matters more than table size alone suggests: every browse read is a
 * `Flow`, and Room re-runs all of them on any write to this table — including the
 * playback-position save that fires every 10 seconds while an unwatched video plays.
 *
 * The leftmost-prefix rule keeps the list short: `["played", "fileName"]` also serves the bare
 * `WHERE played = …` counts, and `["played", "playbackPositionTicks", "fileName"]` serves
 * continue-watching without a second index. `youtubeId` is the primary key and needs none. The
 * write cost is these five B-trees per upsert, which is a sync-time cost paid once against a read
 * win paid continuously.
 *
 * Every index here is asserted on in `VideoDaoInstrumentedTest`, by `EXPLAIN QUERY PLAN` rather
 * than by assumption — one the planner declines to use would be pure write cost. That is why the
 * duration-range index went in schema 2: the library's duration filter was its only reader.
 */
@Entity(
    tableName = "videos",
    indices = [
        // The universal browse order (observeAll and every unfiltered read).
        Index("fileName"),
        // Watched / unwatched lists and their counts.
        Index(value = ["played", "fileName"]),
        // Continue-watching: played = 0 AND playbackPositionTicks > 0, then fileName.
        Index(value = ["played", "playbackPositionTicks", "fileName"]),
        // The Uncategorized filter and countBySource.
        Index(value = ["metadataSource", "fileName"]),
        // The Last played filter: lastPlayedAt > 0 ORDER BY lastPlayedAt DESC (a backward index
        // scan) and its capped count.
        Index(value = ["lastPlayedAt"]),
    ],
)
data class VideoEntity(
    @PrimaryKey val youtubeId: String,
    val jellyfinItemId: String?,
    val fileName: String,
    val title: String,
    val channel: String?,
    val channelId: String?,
    val durationSeconds: Long,
    val uploadDate: String?,
    /**
     * The upload instant in epoch seconds (yt-dlp `timestamp`), null when the feed had none. Rides
     * the metadata merge beside [uploadDate]; the two are rendered together by
     * [com.gmail.volkovskiyda.jellyshelf.util.formatUploadTime]. Added in schema 4.
     */
    val uploadTimestamp: Long? = null,
    val description: String?,
    /** Structured yt-dlp chapters, riding the metadata merge like [description]. */
    val chapters: List<Chapter> = emptyList(),
    val tags: List<String>,
    val youtubeCategories: List<String>,
    val thumbnailUrl: String?,
    val played: Boolean,
    val playbackPositionTicks: Long,
    val playCount: Int,
    /**
     * When this video was last played, epoch millis; 0 = never. Fed from both directions — the
     * app's own playback/toggle writes stamp it, and sync folds in the server's
     * `UserData.LastPlayedDate` (which every Jellyfin client updates). Writes always take
     * `MAX(old, new)`: a timestamp only ever moves forward, so the later value is always the
     * truth regardless of which side it came from.
     */
    val lastPlayedAt: Long = 0L,
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
     * sitting in Uncategorized forever — the sync auto-fill retries it once a day (gated on
     * [lastFetchErrorAt]), and without this the repeated failure is invisible.
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

/**
 * A video the user skipped in the Now playing panel: a queue it is part of still lists it, but
 * playback passes over it until it is unskipped. One row per skipped video, present or absent —
 * there is no "unskipped" row.
 *
 * A table of its own rather than a column on [VideoEntity], for two reasons that both come down to
 * who writes `videos`:
 * - **Sync cannot reset it.** A sync rebuilds every row it sees through `mergeVideo` and upserts it
 *   whole, so a column there is only as safe as that merge remembering to carry it (the way it has
 *   to carry `missedSyncs`). Nothing in a sync ever touches this table.
 * - **The player observes it.** The playback service watches the skip list for as long as a queue
 *   is loaded, and Room re-runs an observed query on every write to the tables it reads — a column
 *   on `videos` would re-query it with each position save, every ten seconds of playback.
 *
 * The foreign key is what keeps it honest the other way: a video that leaves the library — a
 * sync's prune, a removal, signing out — takes its mark with it through the cascade, without every
 * delete path having to remember this table. `videos` is only ever upserted (an update in place,
 * never a delete and re-insert), so a sync that keeps a row keeps its mark.
 */
@Entity(
    tableName = "skipped_videos",
    foreignKeys = [
        ForeignKey(
            entity = VideoEntity::class,
            parentColumns = ["youtubeId"],
            childColumns = ["youtubeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SkippedVideoEntity(
    @PrimaryKey val youtubeId: String,
)
