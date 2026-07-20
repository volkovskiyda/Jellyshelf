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
