@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import android.media.session.MediaController as PlatformController

/**
 * What the platform session — the one the notification, the lock screen, SystemUI, a Bluetooth
 * headset and a paired watch talk to — offers and obeys: every control, and the queue as the
 * timeline holds it, which is [QueueKeeper]'s window around the playing video.
 *
 * Both halves have been lost once. A connection result copied from the synchronous `onConnect`'s
 * default, which media3 answers with an empty sentinel, took play/pause and previous/next with it —
 * on a phone, `dumpsys media_session` showed `actions=128` (rating only) and a headset's next did
 * nothing. And granting the notification's controller everything but the timeline, to save
 * republishing a library-sized queue, emptied a watch's list of what is playing and what is up
 * next. Every other test passed both times.
 *
 * A standalone session wired the way `PlaybackService` wires its own — the default connection, and
 * the keeper moving the window at each transition — rather than the service itself: what is under
 * test is what a platform controller sees and can do across media3's mapping, and a session of its
 * own needs no Koin graph, server or Room behind it.
 */
@RunWith(AndroidJUnit4::class)
class PlatformSessionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var controller: MediaController? = null

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            val keeper = QueueKeeper(NowPlayingQueue()) { player }
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem != null) keeper.reconcile()
                }
            })
            val queue = List(QUEUE_LENGTH) { MediaItem.Builder().setMediaId("v$it").setUri(SAMPLE).build() }
            val playable = keeper.load(queue, START_INDEX, skipped = emptySet())
            player.setMediaItems(playable.items, playable.startIndex, 0L)
            player.prepare()
            session = MediaSession.Builder(context, player).setId(SESSION_ID).build()
        }
        connectAsTheNotification()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            controller?.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun thePlatformSessionOffersEveryControl_andListsTheWindowOfTheQueue() {
        val platform = PlatformController(context, session.platformToken)

        val state = awaitState(platform) { platform.listed().size == 2 * TIMELINE_RADIUS + 1 }

        val actions = state.actions
        assertTrue("play/pause offered (actions=$actions)", actions and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertTrue("next offered (actions=$actions)", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue("previous offered (actions=$actions)", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue("seeking offered (actions=$actions)", actions and PlaybackState.ACTION_SEEK_TO != 0L)
        assertTrue("a jump offered (actions=$actions)", actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L)
        val window = (START_INDEX - TIMELINE_RADIUS..START_INDEX + TIMELINE_RADIUS).map { "v$it" }
        assertEquals(window, platform.listed())
        assertEquals("v$START_INDEX", platform.activeId())
    }

    /** A tap in a watch's list: the id it sends is the one the platform queue gave that row. */
    @Test
    fun aJumpToAListedVideo_playsIt_andTheListFollows() {
        val platform = PlatformController(context, session.platformToken)
        awaitState(platform) { platform.listed().size == 2 * TIMELINE_RADIUS + 1 }
        val last = START_INDEX + TIMELINE_RADIUS
        val row = checkNotNull(platform.queue).single { it.description.mediaId == "v$last" }

        platform.transportControls.skipToQueueItem(row.queueId)

        // The window has moved to the video jumped to, and the list with it.
        awaitState(platform) { platform.activeId() == "v$last" && platform.listed().first() == "v$START_INDEX" }
        assertEquals("v$last", playingId())
        assertEquals("v$last", platform.activeId())
        assertEquals((START_INDEX until QUEUE_LENGTH).map { "v$it" }, platform.listed())
    }

    @Test
    fun nextAndPrevious_moveThroughTheQueue() {
        val platform = PlatformController(context, session.platformToken)
        awaitState(platform) { platform.activeId() == "v$START_INDEX" }

        platform.transportControls.skipToNext()
        awaitState(platform) { platform.activeId() == "v${START_INDEX + 1}" }
        assertEquals("v${START_INDEX + 1}", playingId())

        platform.transportControls.skipToPrevious()
        awaitState(platform) { platform.activeId() == "v$START_INDEX" }
        assertEquals("v$START_INDEX", playingId())
    }

    private fun PlatformController.listed(): List<String?> = queue.orEmpty().map { it.description.mediaId }

    /** The listed video the platform state marks as the one playing. */
    private fun PlatformController.activeId(): String? {
        val active = playbackState?.activeQueueItemId ?: return null
        return queue.orEmpty().firstOrNull { it.queueId == active }?.description?.mediaId
    }

    private fun playingId(): String? {
        var id: String? = null
        instrumentation.runOnMainSync { id = player.currentMediaItem?.mediaId }
        return id
    }

    /** The flag media3's own notification manager connects with — the one the session goes by. */
    private fun connectAsTheNotification() {
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, session.token)
                .setConnectionHints(
                    Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) },
                )
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync()
        }
        controller = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * The platform state once [settled] holds and the controls have been published. The platform
     * session is updated by posting to the main thread, so a read straight after connecting, or
     * after a command, can still see the state from before it; and seeking is offered only once the
     * clip is prepared and known to be seekable, which is why it is part of the wait.
     */
    private fun awaitState(platform: PlatformController, settled: () -> Boolean): PlaybackState {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        val controls = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO
        while (true) {
            val state = platform.playbackState
            val ready = state != null && state.actions and controls == controls && settled()
            if (ready || SystemClock.uptimeMillis() > deadline) return checkNotNull(state) { "no platform state" }
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        /** Long enough that the window reaches neither end of it from [START_INDEX]. */
        const val QUEUE_LENGTH = 60
        const val START_INDEX = 30
        const val SAMPLE = "asset:///demo/sample.mp4"
        const val SESSION_ID = "platform-session-test"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
