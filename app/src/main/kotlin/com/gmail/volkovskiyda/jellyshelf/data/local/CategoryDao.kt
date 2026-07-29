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
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Upsert
    suspend fun upsertCrossRefs(refs: List<VideoCategoryCrossRef>)

    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM video_category vc WHERE vc.categoryId = c.id) AS videoCount " +
            "FROM categories c ORDER BY c.type, c.name"
    )
    fun observeWithCounts(): Flow<List<CategoryWithCount>>

    /**
     * All categories, each with its member count and a [RankedCategory.descriptionMatch] flag —
     * true when any member video's description contains [query]. Relevance ranking and the hard
     * filter (dropping non-matches) are applied in Kotlin on this result (see
     * [com.gmail.volkovskiyda.jellyshelf.data.repository.SearchRanking]); the base order here is
     * just type then name for a stable tie-break.
     *
     * The description match is an uncorrelated IN subquery on purpose: SQLite materializes it
     * once per statement, so the (potentially large) descriptions are scanned once per
     * keystroke instead of once per category row, as a correlated EXISTS would.
     */
    @Query(
        "SELECT c.*, " +
            "(SELECT COUNT(*) FROM video_category vc WHERE vc.categoryId = c.id) AS videoCount, " +
            "(c.id IN (SELECT vc.categoryId FROM video_category vc " +
            "INNER JOIN videos v ON v.youtubeId = vc.youtubeId " +
            "WHERE v.description LIKE '%' || :query || '%' ESCAPE '\\')) AS descriptionMatch " +
            "FROM categories c ORDER BY c.type, c.name"
    )
    fun searchWithCounts(query: String): Flow<List<RankedCategory>>

    /** Drop this video's memberships in auto categories (keeping [keepType], i.e. manual), before re-deriving them. */
    @Query(
        "DELETE FROM video_category WHERE youtubeId = :youtubeId AND categoryId IN " +
            "(SELECT id FROM categories WHERE type != :keepType)"
    )
    suspend fun removeAutoCrossRefsForVideo(youtubeId: String, keepType: String)

    /**
     * Drop every auto-category membership (keeping [keepType], i.e. manual) ahead of a full
     * rebuild during sync, so memberships that no longer apply don't accumulate forever.
     */
    @Query(
        "DELETE FROM video_category WHERE categoryId IN " +
            "(SELECT id FROM categories WHERE type != :keepType)"
    )
    suspend fun clearAutoCrossRefs(keepType: String)

    /** Drop memberships pointing at videos that no longer exist (deleted on the server). */
    @Query("DELETE FROM video_category WHERE youtubeId NOT IN (SELECT youtubeId FROM videos)")
    suspend fun pruneOrphanCrossRefs()

    /** Delete auto categories (keeping [keepType]) that no longer have any members. */
    @Query(
        "DELETE FROM categories WHERE type != :keepType AND id NOT IN " +
            "(SELECT DISTINCT categoryId FROM video_category)"
    )
    suspend fun pruneEmptyCategories(keepType: String)

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM video_category")
    suspend fun clearCrossRefs()
}
