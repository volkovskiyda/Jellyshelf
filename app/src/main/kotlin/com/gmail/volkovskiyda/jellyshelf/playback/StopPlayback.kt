package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.Player

/**
 * Ends playback for good — the one stop behind every "stop" the app offers: the mini-player bar's
 * button, and the player screen's leave once its queue has played out.
 *
 * Three steps, in this order, each for its own reason:
 *
 * - [Player.pause] first, while the player is still `STATE_READY`: that is when [PlaybackService]
 *   records the position, so the stop report carries where the video really was rather than a
 *   position up to a save interval stale. After a bare stop the player is `IDLE` and nothing is
 *   recorded.
 * - [Player.clearMediaItems] next: the queue going empty fires `onMediaItemTransition(null)`, which
 *   is the *single* stop report. Reporting from anywhere else as well would report twice, since the
 *   repository call is fire-and-forget and undeduplicated.
 * - [Player.stop] last, which is what actually lets go of the network and the memory. Clearing the
 *   queue alone does not: media3 1.11.1 releases the queue's own periods on a clear, but not the
 *   preloaded next video's (`MediaPeriodQueue.clear()` leaves the preload pool to `stop()` and
 *   `release()`), and the load control trims its buffer only when told the player stopped. So the
 *   preload kept its stream open and its buffer full until the next video started, or until the
 *   process died. Measured 2026-11-16 on a mid-range API 34 phone after playing and stopping: the
 *   heap still held the preload's HTTP/2 stream with 13 MB of unread video in it, and 4.6 MB of
 *   load-control buffer, 45 minutes later. Coming last, the stop changes nothing the two steps
 *   before it report: the state moves from `ENDED`, where the empty queue left it, to `IDLE`, which
 *   nothing reacts to.
 */
internal fun Player.stopForGood() {
    pause()
    clearMediaItems()
    stop()
}
