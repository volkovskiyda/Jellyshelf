package com.gmail.volkovskiyda.jellyshelf.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.util.escapeLikePattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CategoryDao]'s pruning deletes and its search query against a real SQLite, which is the only
 * place they can be checked: every one of them is raw SQL that the Kotlin compiler cannot verify
 * beyond "it parses", and their subqueries decide what gets *deleted*. A wrong `!=`/`=` in the
 * keep-type clauses would silently eat the user's manual categories.
 *
 * The search half covers what a JVM test cannot reach at all: how *this* SQLite behaves. That
 * `ESCAPE '\'` makes a typed `%` match literally, and — the load-bearing one — that `LIKE` folds
 * non-ASCII case. Upstream SQLite folds ASCII only, which would leave the description match
 * ASCII-only while [com.gmail.volkovskiyda.jellyshelf.data.repository.SearchRanking] folds every
 * other field with Kotlin's Unicode-aware `lowercase()`; Android builds SQLite with ICU, so both
 * sides agree and no folded duplicate column is needed. That is a property of the platform rather
 * than of this code, so it is pinned here — if it ever stops holding (a bundled SQLite without
 * ICU is the realistic way), category search silently goes half-ASCII and only this test says so.
 */
@RunWith(AndroidJUnit4::class)
class CategoryDaoInstrumentedTest {

    private lateinit var db: JellyshelfDatabase
    private lateinit var dao: CategoryDao
    private lateinit var videoDao: VideoDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JellyshelfDatabase::class.java).build()
        dao = db.categoryDao()
        videoDao = db.videoDao()
    }

    @After
    fun tearDown() = db.close()

    private fun video(id: String, description: String? = "desc") = VideoEntity(
        youtubeId = id,
        jellyfinItemId = "jf-$id",
        fileName = "$id.mp4",
        title = "Title $id",
        channel = "Channel",
        channelId = "UC1",
        durationSeconds = 100L,
        uploadDate = "20240101",
        description = description,
        tags = emptyList(),
        youtubeCategories = emptyList(),
        thumbnailUrl = null,
        played = false,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = 1L,
        metadataSource = METADATA_SOURCE_YTDLP,
        metadataUpdatedAt = 0L,
    )

    private fun category(id: String, type: String) =
        CategoryEntity(id = id, name = "Name $id", type = type, createdAt = 0L)

    /** Search exactly the way the repository does — escaped, and otherwise the raw typed query. */
    private suspend fun search(query: String) =
        dao.searchWithCounts(escapeLikePattern(query)).first()

    private suspend fun descriptionMatched(query: String) =
        search(query).filter { it.descriptionMatch }.map { it.category.id }.toSet()

    // --- pruning ------------------------------------------------------------------------------

    @Test
    fun removeAutoCrossRefsForVideo_keepsManualMembership() = runTest {
        videoDao.upsert(listOf(video("v1"), video("v2")))
        dao.upsertAll(listOf(category("auto", "channel"), category("manual", CATEGORY_TYPE_MANUAL)))
        dao.upsertCrossRefs(
            listOf(
                VideoCategoryCrossRef("v1", "auto"),
                VideoCategoryCrossRef("v1", "manual"),
                VideoCategoryCrossRef("v2", "auto"),
            ),
        )

        dao.removeAutoCrossRefsForVideo("v1", keepType = CATEGORY_TYPE_MANUAL)

        val counts = dao.observeWithCounts().first().associate { it.category.id to it.videoCount }
        // v1 left the auto category but kept the manual one, and v2 was not touched.
        assertEquals(1, counts["auto"])
        assertEquals(1, counts["manual"])
    }

    @Test
    fun clearAutoCrossRefs_dropsEveryAutoMembershipButNoManualOne() = runTest {
        videoDao.upsert(listOf(video("v1"), video("v2")))
        dao.upsertAll(listOf(category("auto", "channel"), category("manual", CATEGORY_TYPE_MANUAL)))
        dao.upsertCrossRefs(
            listOf(
                VideoCategoryCrossRef("v1", "auto"),
                VideoCategoryCrossRef("v2", "auto"),
                VideoCategoryCrossRef("v1", "manual"),
            ),
        )

        dao.clearAutoCrossRefs(keepType = CATEGORY_TYPE_MANUAL)

        val counts = dao.observeWithCounts().first().associate { it.category.id to it.videoCount }
        assertEquals(0, counts["auto"])
        assertEquals(1, counts["manual"])
    }

    @Test
    fun pruneOrphanCrossRefs_dropsMembershipsOfDeletedVideos() = runTest {
        videoDao.upsert(listOf(video("kept")))
        dao.upsert(category("c1", "channel"))
        // "gone" has a membership but no video row — what a server-side delete leaves behind.
        dao.upsertCrossRefs(
            listOf(VideoCategoryCrossRef("kept", "c1"), VideoCategoryCrossRef("gone", "c1")),
        )
        assertEquals(2, dao.observeWithCounts().first().single().videoCount)

        dao.pruneOrphanCrossRefs()

        assertEquals(1, dao.observeWithCounts().first().single().videoCount)
    }

    @Test
    fun pruneEmptyCategories_dropsEmptyAutoOnesAndSparesManualAndPopulated() = runTest {
        videoDao.upsert(listOf(video("v1")))
        dao.upsertAll(
            listOf(
                category("populated", "channel"),
                category("emptyAuto", "channel"),
                category("emptyManual", CATEGORY_TYPE_MANUAL),
            ),
        )
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v1", "populated")))

        dao.pruneEmptyCategories(keepType = CATEGORY_TYPE_MANUAL)

        val remaining = dao.observeWithCounts().first().map { it.category.id }.toSet()
        // An empty *manual* category is the user's own, so it survives having no members.
        assertEquals(setOf("populated", "emptyManual"), remaining)
    }

    // --- observeForVideo: the detail screen's "Appears in" read ------------------------------

    @Test
    fun observeForVideo_returnsOnlyThatVideosCategories() = runTest {
        videoDao.upsert(listOf(video("v1"), video("v2")))
        dao.upsertAll(
            listOf(
                category("mine", "channel"),
                category("theirs", "channel"),
                category("shared", "year"),
            ),
        )
        dao.upsertCrossRefs(
            listOf(
                VideoCategoryCrossRef("v1", "mine"),
                VideoCategoryCrossRef("v1", "shared"),
                VideoCategoryCrossRef("v2", "theirs"),
                VideoCategoryCrossRef("v2", "shared"),
            ),
        )

        val ids = dao.observeForVideo("v1").first().map { it.id }.toSet()

        assertEquals(setOf("mine", "shared"), ids)
    }

    /**
     * Type first, then name — the order the "Appears in" section leans on for a multi-valued
     * dimension (several YouTube categories come out alphabetical without the UI re-sorting).
     */
    @Test
    fun observeForVideo_ordersByTypeThenName() = runTest {
        videoDao.upsert(listOf(video("v1")))
        // Names alone would order these [a, b, c]; the type must win first.
        dao.upsertAll(
            listOf(
                CategoryEntity(id = "c", name = "Name c", type = "typeA", createdAt = 0L),
                CategoryEntity(id = "a", name = "Name a", type = "typeB", createdAt = 0L),
                CategoryEntity(id = "b", name = "Name b", type = "typeA", createdAt = 0L),
            ),
        )
        dao.upsertCrossRefs(
            listOf(
                VideoCategoryCrossRef("v1", "a"),
                VideoCategoryCrossRef("v1", "b"),
                VideoCategoryCrossRef("v1", "c"),
            ),
        )

        assertEquals(listOf("b", "c", "a"), dao.observeForVideo("v1").first().map { it.id })
    }

    /** No memberships — an unmatched Jellyfin-only video — is an empty list, not an error. */
    @Test
    fun observeForVideo_isEmptyForAVideoWithNoMemberships() = runTest {
        videoDao.upsert(listOf(video("v1"), video("v2")))
        dao.upsert(category("c1", "channel"))
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v2", "c1")))

        assertTrue(dao.observeForVideo("v1").first().isEmpty())
    }

    // --- search -------------------------------------------------------------------------------

    @Test
    fun searchWithCounts_flagsCategoriesWhoseMemberDescriptionMatches() = runTest {
        videoDao.upsert(listOf(video("v1", description = "a rust tutorial"), video("v2")))
        dao.upsertAll(listOf(category("hit", "channel"), category("miss", "channel")))
        dao.upsertCrossRefs(
            listOf(VideoCategoryCrossRef("v1", "hit"), VideoCategoryCrossRef("v2", "miss")),
        )

        assertEquals(setOf("hit"), descriptionMatched("rust"))
    }

    /**
     * Without `ESCAPE '\'` (and the escaping the repository applies) a typed `%` is a SQL wildcard,
     * so this query would match every category with any described member instead of the one whose
     * description actually contains a percent sign.
     */
    @Test
    fun searchWithCounts_treatsATypedPercentAsLiteral() = runTest {
        videoDao.upsert(
            listOf(video("v1", description = "100% organic"), video("v2", description = "plain")),
        )
        dao.upsertAll(listOf(category("literal", "channel"), category("other", "channel")))
        dao.upsertCrossRefs(
            listOf(VideoCategoryCrossRef("v1", "literal"), VideoCategoryCrossRef("v2", "other")),
        )

        assertEquals(setOf("literal"), descriptionMatched("100%"))
    }

    /** Same for `_`, which LIKE reads as "any single character". */
    @Test
    fun searchWithCounts_treatsATypedUnderscoreAsLiteral() = runTest {
        videoDao.upsert(
            listOf(video("v1", description = "file_name here"), video("v2", description = "fileXname")),
        )
        dao.upsertAll(listOf(category("literal", "channel"), category("other", "channel")))
        dao.upsertCrossRefs(
            listOf(VideoCategoryCrossRef("v1", "literal"), VideoCategoryCrossRef("v2", "other")),
        )

        assertEquals(setOf("literal"), descriptionMatched("file_name"))
    }

    /**
     * Android's SQLite is built with ICU, so `LIKE` folds case for non-ASCII too — verified on
     * device (`lower('ÉCOLE')` returns `école`, which upstream SQLite would not). This is what lets
     * the description match stay in SQL against the raw column and still agree with the
     * Unicode-aware folding the Kotlin ranking applies to titles and channels.
     *
     * Fails on a SQLite without ICU. That is the point: the alternative is a duplicated
     * pre-folded column, and this test is what would justify paying for one.
     */
    @Test
    fun searchWithCounts_foldsNonAsciiCase() = runTest {
        videoDao.upsert(listOf(video("v1", description = "École de cuisine")))
        dao.upsert(category("french", "channel"))
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v1", "french")))

        assertEquals(setOf("french"), descriptionMatched("école"))
    }

    /** The reverse direction, so the test can't pass by accidentally matching case-sensitively. */
    @Test
    fun searchWithCounts_foldsNonAsciiCaseInEitherDirection() = runTest {
        videoDao.upsert(listOf(video("v1", description = "cours d'école")))
        dao.upsert(category("french", "channel"))
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v1", "french")))

        assertEquals(setOf("french"), descriptionMatched("ÉCOLE"))
    }

    @Test
    fun searchWithCounts_stillFoldsAsciiCase() = runTest {
        videoDao.upsert(listOf(video("v1", description = "A Rust Tutorial")))
        dao.upsert(category("rust", "channel"))
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v1", "rust")))

        assertEquals(setOf("rust"), descriptionMatched("RUST"))
    }

    @Test
    fun searchWithCounts_returnsEveryCategoryWithCountsRegardlessOfMatch() = runTest {
        videoDao.upsert(listOf(video("v1", description = "nothing relevant")))
        dao.upsertAll(listOf(category("a", "channel"), category("b", "channel")))
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v1", "a")))

        val rows = search("zzz")

        // The hard filter is Kotlin's job (SearchRanking); the query itself must not drop rows,
        // or the ranking would never see the categories it is supposed to score by name.
        assertEquals(listOf("a", "b"), rows.map { it.category.id })
        assertEquals(1, rows.first { it.category.id == "a" }.videoCount)
        assertTrue(rows.none { it.descriptionMatch })
    }

    @Test
    fun searchWithCounts_ignoresDescriptionlessVideos() = runTest {
        videoDao.upsert(listOf(video("v1", description = null)))
        dao.upsert(category("c1", "channel"))
        dao.upsertCrossRefs(listOf(VideoCategoryCrossRef("v1", "c1")))

        assertFalse(search("anything").single().descriptionMatch)
    }
}
