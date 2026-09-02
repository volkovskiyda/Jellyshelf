package com.gmail.volkovskiyda.jellyshelf.playback

/**
 * Whether opening the player on [youtubeId] should end what is playing first.
 *
 * True for a *different* video, which is the handover this exists for: from the mini-player bar,
 * choosing another video is choosing to stop this one, and the stop belongs to the tap rather than
 * to the moment the new queue finally reaches the session. Everything in between — connecting a
 * controller, snapshotting the origin list, resolving every queued id against the library — is
 * long enough for the previous video to keep playing over the one being opened, and long enough
 * for the player screen's surface, which attaches to whatever the session holds, to keep rendering
 * it there.
 *
 * False when the video asked for is the one already playing: reopening the player from the bar or
 * from a notification tap must leave the session untouched, which is the same rule those paths
 * already follow for the back stack. False when nothing is playing at all — there is nothing to
 * hand over from.
 *
 * A pure function of the state the activity already holds:
 * the rule is worth reading and testing on its own, while the behaviour it drives — a stop report
 * filed at the right instant, a surface that comes up black instead of showing the previous
 * video — needs a server and a decoder to observe.
 */
fun endsCurrentPlayback(nowPlaying: NowPlaying?, youtubeId: String): Boolean =
    nowPlaying != null && nowPlaying.youtubeId != youtubeId
