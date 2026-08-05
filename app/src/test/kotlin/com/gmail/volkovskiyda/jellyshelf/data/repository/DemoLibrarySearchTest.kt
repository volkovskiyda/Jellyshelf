package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search and duration filtering over the **real demo library**, host-side.
 *
 * [SearchRankingTest] pins the ranking rules against three- and four-video fixtures built to
 * isolate one rule each. This runs the same production ranking over the 60 hand-authored demo
 * entries, through the production [demoVideo] mapper — so the fields that actually vary in real
 * content (a description that mentions the query, a channel name shared by eight videos, an entry
 * with no metadata at all) take part. A weighting change that looks harmless against a synthetic
 * pair can reorder a realistic result list, and that is what this notices.
 *
 * Demo data is the right fixture for the whole feature: search and duration filtering never reach
 * Jellyfin — they are a Room read plus in-memory ranking — so there is no server behaviour to
 * cover, and a hand-authored dataset makes the expected results nameable. The SQL half of the
 * filter is covered on-device by `DemoLibrarySearchInstrumentedTest`; scale by `BrowseCostBenchmark`.
 *
 * Read from the module directory rather than the classpath, for the reason [DemoLibraryAssetTest]
 * explains: `assets/` is packaged into the APK, not onto the unit-test classpath.
 */
class DemoLibrarySearchTest {

    private val videos: List<VideoEntity> = File("src/main/assets/demo/library.json").let { file ->
        assertTrue("demo dataset missing at ${file.absolutePath}", file.isFile)
        provideJson().decodeFromString(ListSerializer(IndexEntry.serializer()), file.readText())
    }.mapIndexed { index, entry -> demoVideo(entry, index, now = 0L) }

    private fun search(query: String): List<String> =
        SearchRanking.rankVideos(query, videos).map { it.title }

    @Test
    fun `a query matches every field the ranking reads, and nothing else`() {
        // Two of these are title matches; "A Field Guide to Solder Joints" has neither the word in
        // its title nor its channel — it earns its place through its description ("Cold joints
        // under the scope"), which is the field a synthetic fixture is least likely to model.
        assertEquals(
            listOf(
                "Cold Smoking on a Balcony",
                "Cold Start: Reviving a Seized Engine",
                "Proofing in a Cold Flat",
                "A Field Guide to Solder Joints",
            ),
            search("cold"),
        )
    }

    @Test
    fun `every token must match, so a second word narrows rather than widens`() {
        assertEquals(listOf("Cold Smoking on a Balcony"), search("cold smoking"))
    }

    @Test
    fun `search is case-insensitive over real titles`() {
        assertEquals(search("ferry"), search("FERRY"))
        assertEquals(search("ferry"), search("Ferry"))
    }

    @Test
    fun `a title match leads the channel that shares the word`() {
        val results = search("harbour")
        // One video is named for it; the other eight are the Harbour & Hull channel's. The title
        // has to win — a user typing a title they can see is not asking for the whole channel.
        assertEquals("Harbour Fog, Two Hours", results.first())
        assertEquals("expected the channel's videos behind it, got $results", 9, results.size)
    }

    @Test
    fun `a query nothing matches returns nothing, rather than everything`() {
        assertEquals(emptyList<String>(), search("zeppelin"))
    }

    @Test
    fun `a blank query leaves the browse order untouched`() {
        assertEquals(videos.map { it.title }, search("   "))
    }

    @Test
    fun `the duration buckets partition every video of known length`() {
        val byBucket = DurationBucket.entries.associateWith { bucket ->
            videos.filter { bucket.contains(it.durationSeconds) }
        }

        byBucket.forEach { (bucket, members) ->
            assertTrue("$bucket has no members — the demo dataset must exercise all four", members.isNotEmpty())
            members.forEach {
                assertTrue(
                    "${it.title} (${it.durationSeconds}s) is not in ${bucket.label}",
                    it.durationSeconds >= bucket.minSeconds && it.durationSeconds < bucket.maxSeconds,
                )
            }
        }

        // No video may fall into two buckets, and every video of known length must land in one.
        val placed = byBucket.values.flatten()
        assertEquals("a video landed in two buckets", placed.size, placed.distinct().size)
        assertEquals(videos.filter { it.durationSeconds > 0 }.toSet(), placed.toSet())
    }

    /**
     * The two figures `DemoLibrarySearchFlowTest` reads off the app's own top bar ("8/60"). They are
     * properties of the content rather than of the code, so they are pinned here: an edit to the
     * asset then fails in seconds with a message about the dataset, instead of surfacing later as a
     * count mismatch in a UI test that looks like a broken screen.
     */
    @Test
    fun `the dataset still has the shape the UI flow test asserts on`() {
        assertEquals("demo library size", 60, videos.size)
        assertEquals(
            "videos under ten minutes",
            8,
            videos.count { DurationBucket.UNDER_10.contains(it.durationSeconds) },
        )
        // The query that test types, and the two videos it expects either side of the boundary.
        assertEquals(
            listOf("Ferry Timetables of the Outer Sound", "Night Ferry to Kirkwall"),
            search("ferry"),
        )
        assertTrue(
            "the non-match probe must not match \"ferry\"",
            "A Field Guide to Solder Joints" !in search("ferry"),
        )
    }

    /**
     * The demo's bare entries have no duration, and the filter must drop them rather than sort them
     * into the shortest bucket — a zero-length video is unknown-length, not a zero-minute one.
     */
    @Test
    fun `videos of unknown length belong to no bucket`() {
        val unknown = videos.filter { it.durationSeconds <= 0 }
        assertTrue("the demo dataset needs entries with no duration", unknown.isNotEmpty())
        unknown.forEach {
            assertEquals(
                "${it.title} was bucketed despite an unknown duration",
                null,
                DurationBucket.of(it.durationSeconds),
            )
        }
    }
}
