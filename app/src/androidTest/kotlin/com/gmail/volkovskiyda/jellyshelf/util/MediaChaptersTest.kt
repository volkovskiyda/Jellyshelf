// The hand-built half below constructs media3's @UnstableApi metadata types directly.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.util

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.media3.extractor.metadata.Chapter as MediaChapter

/**
 * [embeddedChapters] against a real file, driven by a real ExoPlayer.
 *
 * Instrumented rather than a unit test because there is nothing here worth faking: the whole
 * question is whether media3's extractors hand us chapters in the shape we expect, and a
 * hand-built `Tracks` would only assert that our own mock matches our own reader. (Robolectric is
 * not an option in this project by decision, and would be the wrong tool regardless — it would stub
 * out the very extractor under test.)
 *
 * **The fixture is the point.** `androidTest/assets/chaptered.mp4` is 3 KB of blank video authored
 * with ffmpeg specifically for this test, and it carries the chapters in **both** MP4 formats at
 * once — a Nero `chpl` atom and a QuickTime chapter track — because ffmpeg writes both and because
 * those are exactly the two media3 1.11.0 added. That doubling is why [embeddedChapters] dedupes
 * by start time; without the dedupe this test sees six chapters, not three, which is the one
 * regression it is most likely to catch.
 *
 * Nothing the shipped app plays reaches this path today: server media carries its own chapters, and
 * the bundled demo clip has none (verified — no `chpl`, no `chap`). This test is therefore the only
 * evidence the feature works at all.
 */
@RunWith(AndroidJUnit4::class)
class MediaChaptersTest {

    @Test
    fun readsChaptersOutOfTheContainer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Copied out of the test APK's assets: ExoPlayer needs a URI it can open, and the fixture
        // lives in the test APK rather than the app's, so `asset:` would look in the wrong one.
        val file = File(context.cacheDir, "chaptered.mp4")
        instrumentation.context.assets.open("chaptered.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }

        val arrived = CountDownLatch(1)
        var chapters: List<Chapter> = emptyList()
        var player: ExoPlayer? = null
        // ExoPlayer is confined to the thread it is built on, and every accessor throws off it
        // since 1.11.0 — so construction, listening and release all go through the main thread.
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        val read = tracks.embeddedChapters()
                        // Tracks can arrive more than once, and the first report may predate the
                        // metadata; only a non-empty read settles it.
                        if (read.isNotEmpty()) {
                            chapters = read
                            arrived.countDown()
                        }
                    }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }

        val settled = arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player?.release() }
        assertTrue("No chapters were ever read out of the fixture.", settled)

        assertEquals(
            listOf(
                Chapter(startMs = 0L, title = "Opening"),
                Chapter(startMs = 3_000L, title = "Middle part"),
                Chapter(startMs = 6_000L, title = "Closing"),
            ),
            chapters,
        )
    }

    /**
     * Hand-built on purpose, unlike the fixture test above — the class KDoc's argument is about
     * not mocking the *extractor*, and this is not about the extractor: both MP4 `chpl`
     * (`BoxParser` normalizes a negative start to `TIME_UNSET` and keeps the entry) and Matroska
     * (a missing `ChapterTimeStart`) provably emit an unset start, and the question is whether
     * *our* filter drops it. Unfiltered it sorts first, is "current" at every position, and its
     * tap hands `seekTo` an unset target.
     */
    @Test
    fun aChapterWithNoReadableStartIsDropped() {
        val format = Format.Builder()
            .setMetadata(
                Metadata(
                    chapter(startTimeMs = C.TIME_UNSET, title = "Ghost"),
                    chapter(startTimeMs = 4_000L, title = "Real"),
                ),
            )
            .build()
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(format),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                ),
            ),
        )

        assertEquals(listOf(Chapter(startMs = 4_000L, title = "Real")), tracks.embeddedChapters())
    }

    private fun chapter(startTimeMs: Long, title: String): MediaChapter = MediaChapter.Builder()
        .setStartTimeMs(startTimeMs)
        .setTitle(Label(null, title))
        .build()

    private companion object {
        /** Generous: this is a 3 KB local file, so anything slower than this is a real failure. */
        const val TIMEOUT_SECONDS = 15L
    }
}
