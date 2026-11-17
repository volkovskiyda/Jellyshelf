package com.gmail.volkovskiyda.jellyshelf.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The video after the current one, as the service's own [buildPlayer] preloads it — and what a stop
 * does to it.
 *
 * Both items are `androidTest/assets/long-blank.mp4`: two minutes of 176×144 black at 5 fps, 15 KB in
 * all. Not smaller: a phone's hardware decoder has a minimum frame size — 96×96 on a mid-range
 * API 34 phone, whose decoder refused a 64×48 clip outright — and media3 picks it over the
 * software one. Two minutes is longer than the player's 50-second buffer, so the current video is never fully
 * loaded and the next one stays in media3's preload pool rather than joining the queue. And media3's
 * loading check is cut from a megabyte to a kilobyte, so the preload stops partway through the file
 * with its stream still open, the way a real one stops a few seconds into a real video.
 *
 * The data source records which streams open and close, by file name: the clip is copied out twice,
 * as `current.mp4` and `next.mp4`.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreloadedNextVideoTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer

    /** "open next", "close next", "state 4", ... — written on loader threads, read on the test's. */
    private val events = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = onMain {
        // Copied out of the test APK, whose context media3's asset reader cannot use (it has no
        // application context), into two files so the streams can be told apart by name.
        val clips = File(instrumentation.targetContext.cacheDir, "preloaded-next-video").apply { mkdirs() }
        val (current, next) = listOf("current", "next").map { name ->
            File(clips, "$name.mp4").also { file ->
                instrumentation.context.assets.open(CLIP).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
        val files = DefaultDataSource.Factory(instrumentation.targetContext)
        val recording = DataSource.Factory { RecordingDataSource(files.createDataSource(), events) }
        player = buildPlayer(
            instrumentation.targetContext,
            recording,
            KeyframeStartSourceFactory(
                ProgressiveMediaSource.Factory(recording).setContinueLoadingCheckIntervalBytes(CHECK_INTERVAL_BYTES),
            ),
        )
        // Without audio focus, for the reason VideoTrackReenableTest gives: where the platform
        // refuses it to a test, the current video would sit paused rather than play.
        player.setAudioAttributes(player.audioAttributes, false)
        // Into the same log, so a failure says what the player was doing as well as the streams.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                events += "state $playbackState"
            }

            override fun onPlayerError(error: PlaybackException) {
                events += "error ${error.errorCodeName}: ${error.cause}"
            }
        })
        player.setMediaItems(
            listOf(
                MediaItem.Builder().setMediaId("current").setUri(Uri.fromFile(current)).build(),
                MediaItem.Builder().setMediaId("next").setUri(Uri.fromFile(next)).build(),
            ),
        )
        player.prepare()
        player.play()
    }

    @After
    fun tearDown() = onMain { player.release() }

    /**
     * Stopping lets go of the preloaded video's stream. Clearing the queue alone left it open — its
     * period alive in media3's preload pool — until the next video started or the process died,
     * which on a real server is an HTTP/2 stream filling with up to 16 MB of video nobody reads.
     */
    @Test
    fun stopping_closesThePreloadedVideosStream() {
        awaitEvent("open next")
        SystemClock.sleep(SETTLE_MS)
        assertTrue("the preload should still be open before the stop: $events", "close next" !in events)

        onMain { player.stopForGood() }

        awaitEvent("close next")
    }

    private fun awaitEvent(event: String) {
        val deadline = SystemClock.elapsedRealtime() + EVENT_TIMEOUT_MS
        while (event !in events && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(POLL_MS)
        assertTrue("no \"$event\" within $EVENT_TIMEOUT_MS ms: $events", event in events)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** [upstream], recording each open and close by the file's name: `current` or `next`. */
    private class RecordingDataSource(
        private val upstream: DataSource,
        private val events: MutableList<String>,
    ) : DataSource {
        private var name: String? = null

        override fun addTransferListener(transferListener: TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val opened = dataSpec.uri.lastPathSegment.orEmpty().removeSuffix(".mp4")
            name = opened
            events += "open $opened"
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun close() {
            name?.let { events += "close $it" }
            name = null
            upstream.close()
        }
    }

    private companion object {
        const val CLIP = "long-blank.mp4"
        const val CHECK_INTERVAL_BYTES = 1024
        const val SETTLE_MS = 500L
        const val EVENT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
