package com.gmail.volkovskiyda.jellyshelf.data.remote

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
 * It also pins the asset to the [IndexEntry] format: `assets/demo/library.json` is documented as a
 * worked example of what `scripts/build-library-index.sh` emits, so a change to that format that
 * left the sample behind would be a documentation bug as much as a demo one.
 *
 * Read from the module directory rather than the classpath: `assets/` is packaged into the APK,
 * not onto the unit-test classpath, and this project has no Robolectric to serve it (a deliberate
 * decision). Gradle runs unit tests with the module as the working directory.
 */
class DemoLibraryAssetTest {

    private val entries: List<IndexEntry> = File("src/main/assets/demo/library.json")
        .let { file ->
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
        val bare = entries.filter {
            it.channel == null && it.duration == null && it.uploadDate == null &&
                it.description == null && it.categories.isNullOrEmpty() && it.tags.isNullOrEmpty()
        }
        assertTrue("expected a few metadata-less entries, got ${bare.size}", bare.size in 2..8)
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
        entries.mapNotNull { it.thumbnail }.distinct().forEach { url ->
            assertTrue("demo thumbnails must be bundled assets: $url", url.startsWith(prefix))
            val file = File("src/main/assets/${url.removePrefix(prefix)}")
            assertTrue("missing bundled thumbnail: ${file.path}", file.isFile)
        }
    }
}
