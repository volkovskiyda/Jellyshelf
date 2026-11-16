@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import android.media.session.MediaController as PlatformController

/**
 * What the platform session — the one the notification, the lock screen, SystemUI and a Bluetooth
 * headset talk to — offers once the media notification's controller is connected with
 * [notificationControllerConnection]: every control, and no queue.
 *
 * The controls are the half worth pinning. The first version of this copied its commands from the
 * synchronous `onConnect`'s default, which media3 answers with an empty sentinel: the queue went, and
 * so did play/pause and previous/next — on a phone, `dumpsys media_session` showed
 * `actions=128` (rating only) and a headset's next did nothing. Every other test still passed.
 *
 * A standalone session wired the way `PlaybackService.SessionCallback` wires it, rather than the
 * service itself: what is under test is media3's mapping from that controller's commands onto the
 * platform session, and a session of its own needs no Koin graph, server or Room behind it.
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
            player.setMediaItems(
                List(QUEUE_LENGTH) { MediaItem.Builder().setMediaId("v$it").setUri(SAMPLE).build() },
            )
            player.prepare()
            session = MediaSession.Builder(context, player)
                .setId(SESSION_ID)
                .setCallback(object : MediaSession.Callback {
                    override fun onConnectAsync(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                    ): ListenableFuture<MediaSession.ConnectionResult> =
                        if (session.isMediaNotificationController(controller)) {
                            Futures.immediateFuture(notificationControllerConnection(session, controller))
                        } else {
                            super.onConnectAsync(session, controller)
                        }
                })
                .build()
        }
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
    fun thePlatformSessionKeepsEveryControl_butNotTheQueue() {
        connectAsTheNotification()
        val platform = PlatformController(context, session.platformToken)

        val state = awaitState(platform) { platform.queue.isNullOrEmpty() }

        val actions = state.actions
        assertTrue("play/pause offered (actions=$actions)", actions and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertTrue("next offered (actions=$actions)", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue("previous offered (actions=$actions)", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue("seeking offered (actions=$actions)", actions and PlaybackState.ACTION_SEEK_TO != 0L)
        assertTrue("no queue (size ${platform.queue?.size})", platform.queue.isNullOrEmpty())
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
     * session is updated by posting to the main thread, so a read straight after connecting can
     * still see the state from before it; and seeking is offered only once the clip is prepared and
     * known to be seekable, which is why it is part of the wait.
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
        const val QUEUE_LENGTH = 3
        const val SAMPLE = "asset:///demo/sample.mp4"
        const val SESSION_ID = "platform-session-test"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
