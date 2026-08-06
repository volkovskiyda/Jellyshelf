package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every list query comes in two versions. The `*Browse` twin returns [VideoBrowseRow] — the columns
 * a list row renders — and is what the browse paths use; the full-row version stays for ranked
 * search, which scores `description`. See [VideoBrowseRow] for why that split is a type rather than
 * a partially-selected [VideoEntity].
 *
 * The twins are `SELECT *` plus [RewriteQueriesToDropUnusedColumns] rather than hand-written column
 * lists, so the projection cannot drift out of sync with [VideoBrowseRow] when a field is added or
 * removed — Room derives the list from the return type at compile time.
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

    // --- Virtual "Others" filters: live lists ---

    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    fun observeBySource(source: String): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    fun observeBySourceBrowse(source: String): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    suspend fun getBySource(source: String): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
    fun observeWatched(): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
    fun observeWatchedBrowse(): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    fun observeUnwatched(): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    fun observeUnwatchedBrowse(): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName")
    fun observeContinueWatching(): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName")
    fun observeContinueWatchingBrowse(): Flow<List<VideoBrowseRow>>

    @Query("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
    suspend fun getWatched(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    suspend fun getUnwatched(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName")
    suspend fun getContinueWatching(): List<VideoEntity>

    // --- Virtual "Others" filters: live counts ---

    @Query("SELECT COUNT(*) FROM videos WHERE metadataSource = :source")
    fun countBySource(source: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM videos WHERE played = 1")
    fun countWatched(): Flow<Int>

    @Query("SELECT COUNT(*) FROM videos WHERE played = 0")
    fun countUnwatched(): Flow<Int>

    @Query("SELECT COUNT(*) FROM videos WHERE played = 0 AND playbackPositionTicks > 0")
    fun countContinueWatching(): Flow<Int>

    /**
     * Videos whose duration is in [[minSeconds], [maxSeconds]) — a hard filter — ordered by file
     * name. Relevance ranking against the search query is applied in Kotlin on the result (see
     * [com.gmail.volkovskiyda.jellyshelf.data.repository.SearchRanking]), so the query itself lives
     * outside SQL.
     */
    @Query(
        "SELECT * FROM videos " +
            "WHERE durationSeconds >= :minSeconds AND durationSeconds < :maxSeconds " +
            "ORDER BY fileName"
    )
    fun observeByDurationRange(minSeconds: Long, maxSeconds: Long): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM videos " +
            "WHERE durationSeconds >= :minSeconds AND durationSeconds < :maxSeconds " +
            "ORDER BY fileName"
    )
    fun observeByDurationRangeBrowse(minSeconds: Long, maxSeconds: Long): Flow<List<VideoBrowseRow>>

    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.fileName"
    )
    fun observeByCategory(categoryId: String): Flow<List<VideoEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.fileName"
    )
    fun observeByCategoryBrowse(categoryId: String): Flow<List<VideoBrowseRow>>

    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.fileName"
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
