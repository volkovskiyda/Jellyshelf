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

    @Query("SELECT * FROM videos ORDER BY uploadDate DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query(
        "SELECT * FROM videos WHERE title LIKE '%' || :query || '%' " +
            "OR channel LIKE '%' || :query || '%' ORDER BY uploadDate DESC"
    )
    fun search(query: String): Flow<List<VideoEntity>>

    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN video_category vc ON vc.youtubeId = v.youtubeId " +
            "WHERE vc.categoryId = :categoryId ORDER BY v.uploadDate DESC"
    )
    fun observeByCategory(categoryId: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE youtubeId = :youtubeId")
    fun observe(youtubeId: String): Flow<VideoEntity?>

    @Query("SELECT * FROM videos WHERE youtubeId = :youtubeId")
    suspend fun get(youtubeId: String): VideoEntity?

    @Query("UPDATE videos SET played = :played, playbackPositionTicks = :positionTicks WHERE youtubeId = :youtubeId")
    suspend fun updateWatchState(youtubeId: String, played: Boolean, positionTicks: Long)

    @Query("SELECT COUNT(*) FROM videos")
    fun count(): Flow<Int>
}
