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

    /**
     * Videos whose duration is in [[minSeconds], [maxSeconds]) — a hard filter — with those whose
     * title or channel match [query] sorted first and the rest after. A blank [query] leaves every
     * row in the first group, so the result is simply the duration-filtered list by file name.
     */
    @Query(
        "SELECT * FROM videos " +
            "WHERE durationSeconds >= :minSeconds AND durationSeconds < :maxSeconds " +
            "ORDER BY (CASE WHEN :query = '' " +
            "OR title LIKE '%' || :query || '%' OR channel LIKE '%' || :query || '%' " +
            "THEN 0 ELSE 1 END), fileName"
    )
    fun search(query: String, minSeconds: Long, maxSeconds: Long): Flow<List<VideoEntity>>

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

    @Query("DELETE FROM videos")
    suspend fun clear()
}
