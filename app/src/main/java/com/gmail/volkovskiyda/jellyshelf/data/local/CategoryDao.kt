package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertCrossRefs(refs: List<VideoCategoryCrossRef>)

    @Upsert
    suspend fun upsertCrossRef(ref: VideoCategoryCrossRef)

    @Query("DELETE FROM video_category WHERE youtubeId = :youtubeId AND categoryId = :categoryId")
    suspend fun removeCrossRef(youtubeId: String, categoryId: String)

    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM video_category vc WHERE vc.categoryId = c.id) AS videoCount " +
            "FROM categories c ORDER BY c.type, c.name"
    )
    fun observeWithCounts(): Flow<List<CategoryWithCount>>

    /**
     * Search categories by name (uploader) or, with lower priority, by the description of
     * any video they contain. Name matches sort first; within each group by type then name.
     */
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM video_category vc WHERE vc.categoryId = c.id) AS videoCount " +
            "FROM categories c " +
            "WHERE c.name LIKE '%' || :query || '%' " +
            "OR EXISTS (SELECT 1 FROM video_category vc " +
            "INNER JOIN videos v ON v.youtubeId = vc.youtubeId " +
            "WHERE vc.categoryId = c.id AND v.description LIKE '%' || :query || '%') " +
            "ORDER BY (CASE WHEN c.name LIKE '%' || :query || '%' THEN 0 ELSE 1 END), c.type, c.name"
    )
    fun searchWithCounts(query: String): Flow<List<CategoryWithCount>>

    @Query(
        "SELECT c.* FROM categories c " +
            "INNER JOIN video_category vc ON vc.categoryId = c.id " +
            "WHERE vc.youtubeId = :youtubeId ORDER BY c.name"
    )
    fun observeForVideo(youtubeId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name")
    fun observeByType(type: String): Flow<List<CategoryEntity>>

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: String)

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM video_category")
    suspend fun clearCrossRefs()
}
