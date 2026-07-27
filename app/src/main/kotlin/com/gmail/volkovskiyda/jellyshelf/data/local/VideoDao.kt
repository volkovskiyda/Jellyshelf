package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Upsert
    suspend fun upsert(videos: List<VideoEntity>)

    @Upsert
    suspend fun upsert(video: VideoEntity)

    @Query("SELECT * FROM videos ORDER BY fileName")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos")
    suspend fun getAll(): List<VideoEntity>

    // --- Virtual "Others" filters: live lists ---

    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    fun observeBySource(source: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE metadataSource = :source ORDER BY fileName")
    suspend fun getBySource(source: String): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE played = 1 ORDER BY fileName")
    fun observeWatched(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE played = 0 ORDER BY fileName")
    fun observeUnwatched(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE played = 0 AND playbackPositionTicks > 0 ORDER BY fileName")
    fun observeContinueWatching(): Flow<List<VideoEntity>>

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

    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.fileName"
    )
    fun observeByCategory(categoryId: String): Flow<List<VideoEntity>>

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

    @Query("DELETE FROM videos")
    suspend fun clear()
}
