package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import kotlinx.coroutines.flow.Flow

/**
 * The skip list ([SkippedVideoEntity]). Callers chunk id lists, as for [VideoDao.getByIds].
 */
@Dao
interface SkipDao {
    /**
     * Skips every stored video among [youtubeIds]. An id with no row is left out rather than
     * failing the foreign key — a video that left the library between the tap and the write has
     * nothing left to skip — and one already skipped stays as it is.
     */
    @Query(
        "INSERT OR IGNORE INTO skipped_videos (youtubeId) " +
            "SELECT youtubeId FROM videos WHERE youtubeId IN (:youtubeIds)",
    )
    suspend fun skip(youtubeIds: List<String>)

    @Query("DELETE FROM skipped_videos WHERE youtubeId IN (:youtubeIds)")
    suspend fun unskip(youtubeIds: List<String>)

    /** Reads this table alone, so a write to `videos` — a position save — does not re-run it. */
    @Query("SELECT youtubeId FROM skipped_videos")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT youtubeId FROM skipped_videos")
    suspend fun getIds(): List<String>

    /** Every row here has its video — the cascade sees to that — so this is the filter's size. */
    @Query("SELECT COUNT(*) FROM skipped_videos")
    fun count(): Flow<Int>

    /** The Others tab's Skipped filter, in the library's own order. */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT v.* FROM videos v INNER JOIN skipped_videos s ON s.youtubeId = v.youtubeId " +
            "ORDER BY v.fileName",
    )
    fun observeSkippedBrowse(): Flow<List<VideoBrowseRow>>
}
