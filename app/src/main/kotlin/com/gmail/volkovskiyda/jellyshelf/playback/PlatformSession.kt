@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * What the media notification's controller is granted, and so what the platform session offers —
 * media3 hands the platform session that controller's commands: every default command but the
 * timeline, which keeps the queue out of it.
 *
 * With the timeline, media3 republishes the whole queue to the platform session on every timeline
 * change: every queued video turned into a platform queue item on the main thread, parcelled to
 * system_server, and handed on to whatever listens there — SystemUI, and Bluetooth's AVRCP target
 * while a headset is connected. A library tap queues the whole library, and a timeline changes
 * several times per video (the queue arriving, each item's duration becoming known), so with 1,245
 * videos that was ~15 ms of main thread per change on a mid-range API 34 phone (2026-11-15), the
 * first inside the ~110 ms block between a tap's resolve and the first byte of video. Nothing on
 * that side shows the queue: the notification, the lock screen and a headset need the current item,
 * play/pause and previous/next, and those are commands of their own. The player's queue panel
 * reads [NowPlayingQueue], not the session.
 *
 * Built from the session's own defaults for the controller, never from the result of the
 * synchronous `MediaSession.Callback.onConnect`: that one is deprecated, and its default answers with
 * a sentinel holding no commands at all — copied, it left the platform session with no play/pause
 * and no previous/next (`PlatformSessionTest`).
 */
internal fun notificationControllerConnection(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
): MediaSession.ConnectionResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
    .setAvailablePlayerCommands(
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .remove(Player.COMMAND_GET_TIMELINE)
            .build(),
    )
    .build()
