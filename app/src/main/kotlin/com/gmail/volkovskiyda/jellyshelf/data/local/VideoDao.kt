package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every live list query is a `*Browse` one returning [VideoBrowseRow] — the columns a list row
 * renders, and nothing else. See [VideoBrowseRow] for why that is a distinct type rather than a
 * partially-selected [VideoEntity].
 *
 * One of them, [observeAll], keeps a full-row version beside the projected one: it also feeds
 * ranked search, which scores `description`. The others have no full-row version at all — nothing
 * was left to read one.
 *
 * The projected queries are `SELECT *` plus [RewriteQueriesToDropUnusedColumns] rather than
 * hand-written column lists, so the projection cannot drift out of sync with [VideoBrowseRow] when
 * a field is added or removed — Room derives the list from the return type at compile time.
 *
 * The `suspend get*` reads are untouched by all of this: they serve sync, metadata backfill, bulk
 * removal and playlist creation, which need whole rows.
 */
@Dao
@Suppress("TooManyFunctions") // a Room DAO is one function per query, by design
interface VideoDao {
    @Upsert
    suspend fun upsert(videos: List<VideoEntity>)

    @Upsert
    suspend fun upsert(video: VideoEntity)

    @Query("SELECT * FROM videos ORDER BY fileName")
    fun observeAll(): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos ORDER BY fileName")
    fun observeAllBrowse(): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    /**
     * The rows behind a multi-selection, in the file-name order every list on screen is in — so a
     * bulk run works through them top to bottom, the way the user sees them.
     *
     * Callers chunk the id list rather than passing it whole: SQLite caps how many variables one
     * statement may bind (999 on the platform versions this app supports), and selecting every
     * video in a real library goes well past that.
     */
    @Query("SELECT * FROM videos WHERE youtubeId IN (:youtubeIds) ORDER BY fileName")
    suspend fun getByIds(youtubeIds: List<String>): List<VideoEntity>

    // --- Virtual "Others" filters: live lists ---

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    fun observeBySourceBrowse(source: String): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    suspend fun getBySource(source: String): List<VideoEntity>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
    fun observeWatchedBrowse(): Flow<List<VideoBrowseRow>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    fun observeUnwatchedBrowse(): Flow<List<VideoBrowseRow>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName")
    fun observeContinueWatchingBrowse(): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
    suspend fun getWatched(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    suspend fun getUnwatched(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName")
    suspend fun getContinueWatching(): List<VideoEntity>

    /**
     * Videos the last syncs stopped seeing on the server — the same predicate as
     * [com.gmail.volkovskiyda.jellyshelf.domain.model.Video.missingFromServer].
     *
     * The one browse query here with no index behind it: `missedSyncs` is 0 for all but a handful
     * of rows, so this and [countMissing] scan the table. That is deliberate — an index would mean
     * a schema version bump and a migration for a filter that is only ever mounted on the
     * Categories screen and the screen it opens, both of which stop observing seconds after they
     * leave. Revisit it if either ever moves somewhere always-on.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE missedSyncs > 0 ORDER BY fileName")
    fun observeMissingBrowse(): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE missedSyncs > 0 ORDER BY fileName")
    suspend fun getMissing(): List<VideoEntity>

    // --- Virtual "Others" filters: live counts ---

    @Query("SELECT COUNT(*) FROM videos WHERE metadataSource = :source")
    fun countBySource(source: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM videos WHERE played = 1")
    fun countWatched(): Flow<Int>

    @Query("SELECT COUNT(*) FROM videos WHERE played = 0")
    fun countUnwatched(): Flow<Int>

    @Query("SELECT COUNT(*) FROM videos WHERE played = 0 AND playbackPositionTicks > 0")
    fun countContinueWatching(): Flow<Int>

    /** See [observeMissingBrowse] for why this one has no index behind it. */
    @Query("SELECT COUNT(*) FROM videos WHERE missedSyncs > 0")
    fun countMissing(): Flow<Int>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.fileName",
    )
    fun observeByCategoryBrowse(categoryId: String): Flow<List<VideoBrowseRow>>

    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.fileName",
    )
    suspend fun getByCategory(categoryId: String): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE youtubeId = :youtubeId")
    fun observe(youtubeId: String): Flow<VideoEntity?>

    @Query("SELECT * FROM videos WHERE youtubeId = :youtubeId")
    suspend fun get(youtubeId: String): VideoEntity?

    @Query("UPDATE videos SET played = :played, playbackPositionTicks = :positionTicks WHERE youtubeId = :youtubeId")
    suspend fun updateWatchState(youtubeId: String, played: Boolean, positionTicks: Long)

    /**
     * Watch state as the *server* reports it, its play count included — for mirroring one
     * authoritative snapshot into the row after playback stops.
     *
     * The write above deliberately has no play count to carry: nothing local counts plays, so a
     * local decision can only leave that number where it is. Only the server increments it, and
     * only this write brings it back.
     */
    @Query(
        "UPDATE videos SET played = :played, playbackPositionTicks = :positionTicks, playCount = :playCount " +
            "WHERE youtubeId = :youtubeId",
    )
    suspend fun updateServerWatchState(
        youtubeId: String,
        played: Boolean,
        positionTicks: Long,
        playCount: Int,
    )

    /**
     * Position-only write for the in-app player's periodic saves. The `played = 0` guard is the
     * point: a save racing a completion report must neither flip watch state nor resurrect the
     * cleared position of a row just marked played.
     */
    @Query("UPDATE videos SET playbackPositionTicks = :positionTicks WHERE youtubeId = :youtubeId AND played = 0")
    suspend fun updatePlaybackPosition(youtubeId: String, positionTicks: Long)

    @Query("SELECT COUNT(*) FROM videos")
    fun count(): Flow<Int>

    /**
     * Removes rows the last sync didn't touch — i.e. videos deleted or renamed on the server.
     * Every row a sync keeps is stamped with that sync's [syncedAt], so anything else is stale.
     *
     * Immediate, no grace period: only for deletions the user asked for (a narrowed library
     * scope). Server-side disappearances go through [markMissedSince]/[deleteAfterMissedSyncs].
     */
    @Query("DELETE FROM videos WHERE lastSyncedAt != :syncedAt")
    suspend fun deleteNotSyncedAt(syncedAt: Long)

    /**
     * Counts one missed sync against every row [syncedAt]'s sync didn't see, instead of deleting
     * it outright. A Jellyfin library mid-rescan, or a page that shifted under the sync's paging,
     * makes rows disappear from one listing and come back in the next; deleting on the first miss
     * would drop their manual category memberships and fetched yt-dlp metadata for good.
     */
    @Query("UPDATE videos SET missedSyncs = missedSyncs + 1 WHERE lastSyncedAt != :syncedAt")
    suspend fun markMissedSince(syncedAt: Long)

    /**
     * Removes the rows still missing from [syncedAt]'s sync after [maxMissedSyncs] consecutive
     * misses — by then the video really is gone from the server. Run right after
     * [markMissedSince], whose increment this counts.
     */
    @Query("DELETE FROM videos WHERE lastSyncedAt != :syncedAt AND missedSyncs >= :maxMissedSyncs")
    suspend fun deleteAfterMissedSyncs(syncedAt: Long, maxMissedSyncs: Int)

    /** Drops a single video the user chose not to wait out the grace period for. */
    @Query("DELETE FROM videos WHERE youtubeId = :youtubeId")
    suspend fun delete(youtubeId: String)

    @Query("DELETE FROM videos")
    suspend fun clear()
}
