package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.data.repository.hasIndexMetadata
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.util.parseTimecodes
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the bundled demo dataset, host-side: it is hand-authored content, and the promises the
 * demo makes about it — every duration bucket occupied, several years of upload dates, both
 * chapter shapes, members for the Uncategorized filter — are invisible until something renders it
 * on a device. These assertions fail on a bad edit at `./gradlew test` speed instead.
 *
 * Both demo documents are covered: the seed (`library.json`) and the metadata a demo fetch
 * discovers for the entries the seed leaves bare (`fetched.json`, served by [DemoBackend]). They
 * only make sense as a pair, which is the one thing an edit to either can silently break.
 *
 * It also pins the assets to the [IndexEntry] format: `assets/demo/library.json` is documented as a
 * worked example of what `scripts/build-library-index.sh` emits, so a change to that format that
 * left the sample behind would be a documentation bug as much as a demo one.
 *
 * Read from the module directory rather than the classpath: `assets/` is packaged into the APK,
 * not onto the unit-test classpath, and this project has no Robolectric to serve it (a deliberate
 * decision). Gradle runs unit tests with the module as the working directory.
 */
class DemoLibraryAssetTest {

    private val entries: List<IndexEntry> = read("library.json")
    private val fetched: List<IndexEntry> = read("fetched.json")

    private fun read(name: String): List<IndexEntry> = File("src/main/assets/demo/$name").let { file ->
        assertTrue("demo dataset missing at ${file.absolutePath}", file.isFile)
        provideJson().decodeFromString(ListSerializer(IndexEntry.serializer()), file.readText())
    }

    @Test
    fun demoDatasetParsesAsAnIndexDocument() {
        assertTrue("expected a substantial library, got ${entries.size}", entries.size >= 50)
        assertEquals("ids must be unique", entries.size, entries.map { it.id }.distinct().size)
        // 11 characters is the YouTube id shape the whole app assumes; these are invented, not real.
        assertTrue("every id is YouTube-shaped", entries.all { it.id.length == 11 })
        assertTrue("every entry has a title", entries.all { !it.title.isNullOrBlank() })
    }

    @Test
    fun demoDatasetCoversEveryAutoCategoryDimension() {
        val withDuration = entries.mapNotNull { it.duration }.filter { it > 0 }
        val buckets = withDuration.mapNotNull { DurationBucket.of(it) }.toSet()
        assertEquals("every duration bucket needs members", DurationBucket.entries.toSet(), buckets)

        val years = entries.mapNotNull { it.uploadDate?.take(4) }.toSet()
        assertTrue("upload dates must span several years, got $years", years.size >= 3)
        val months = entries.mapNotNull { it.uploadDate?.take(6) }.toSet()
        assertTrue("upload months must spread, got ${months.size}", months.size >= 12)

        val channels = entries.mapNotNull { it.channel }.toSet()
        assertTrue("expected a handful of channels, got $channels", channels.size >= 5)
        val categories = entries.flatMap { it.categories.orEmpty() }.toSet()
        assertTrue("expected several YouTube categories, got $categories", categories.size >= 5)
    }

    /** Rows with nothing but a title are what gives the "Uncategorized" virtual filter members. */
    @Test
    fun demoDatasetKeepsAFewEntriesDeliberatelyBare() {
        // The repository's own predicate, not a copy of it: it is what decides at seed time which
        // entries become Jellyfin-sourced, and two definitions of "bare" would drift apart.
        val bare = entries.filterNot { it.hasIndexMetadata }
        assertTrue("expected a few metadata-less entries, got ${bare.size}", bare.size in 2..8)
    }

    /**
     * The bare entries are the whole subject of the demo's "Fetch metadata for N missing": without
     * a counterpart here, that action can only ever report failures.
     */
    @Test
    fun everyBareEntryHasTheMetadataADemoFetchWouldFind() {
        val bare = entries.filterNot { it.hasIndexMetadata }
        val fetchedById = fetched.associateBy { it.id }
        assertEquals(
            "every bare demo entry needs fetched metadata",
            emptyList<String>(),
            bare.map { it.id }.filterNot { it in fetchedById },
        )
        assertEquals(
            "fetched.json must not describe videos the demo library doesn't have",
            emptyList<String>(),
            fetched.map { it.id } - entries.map { it.id }.toSet(),
        )
        assertTrue("fetched metadata has to be metadata", fetched.all { it.hasIndexMetadata })
        // A fetch that left the file name as the title would look like it had done nothing.
        val unchanged = bare.filter { fetchedById.getValue(it.id).title == it.title }
        assertEquals("a fetch must visibly retitle the row", emptyList<String>(), unchanged.map { it.id })
    }

    /** Fetched rows join the library's own channels and categories rather than inventing new ones. */
    @Test
    fun fetchedMetadataJoinsTheExistingCategories() {
        val channels = entries.mapNotNull { it.channel }.toSet()
        val categories = entries.flatMap { it.categories.orEmpty() }.toSet()
        assertEquals(
            "fetched channels must be channels the demo already has",
            emptyList<String>(),
            fetched.mapNotNull { it.channel }.filterNot { it in channels },
        )
        assertEquals(
            "fetched categories must be categories the demo already has",
            emptyList<String>(),
            fetched.flatMap { it.categories.orEmpty() }.filterNot { it in categories },
        )
    }

    /** Both chapter sources have to be exercised, including the case where they disagree. */
    @Test
    fun demoDatasetExercisesBothChapterSources() {
        val timecoded = entries.filter {
            parseTimecodes(it.description, it.duration ?: 0L).isNotEmpty()
        }
        assertTrue("expected description timecodes, got ${timecoded.size}", timecoded.size >= 3)

        val structured = entries.filter { !it.chapters.isNullOrEmpty() }
        assertTrue("expected structured chapters, got ${structured.size}", structured.size >= 2)

        assertTrue(
            "one entry must carry both, so the description-wins priority is demoable",
            entries.any {
                !it.chapters.isNullOrEmpty() &&
                    parseTimecodes(it.description, it.duration ?: 0L).isNotEmpty()
            },
        )
    }

    /** A thumbnail naming a file that isn't packaged renders as a blank card on every screen. */
    @Test
    fun everyDemoThumbnailPointsAtABundledAsset() {
        val prefix = "file:///android_asset/"
        (entries + fetched).mapNotNull { it.thumbnail }.distinct().forEach { url ->
            assertTrue("demo thumbnails must be bundled assets: $url", url.startsWith(prefix))
            val file = File("src/main/assets/${url.removePrefix(prefix)}")
            assertTrue("missing bundled thumbnail: ${file.path}", file.isFile)
        }
    }
}
