package com.gmail.volkovskiyda.jellyshelf.navigation

import androidx.navigation3.runtime.NavKey

/**
 * The back stack after the player's minimize: the Player entry on top gives way to the Detail
 * screen of [nowPlayingId] — what is playing *now*, which the queue may have moved on from the
 * video the player was opened with — and playback keeps running underneath.
 *
 * Which entries change depends on what sat under the player:
 *
 *  - **A Detail of the playing video** is simply landed on. That is today's plain pop, and covers
 *    "still on the video whose page you pressed Play on".
 *  - **The Detail the player was launched from** (the one Play was pressed on, same id as the
 *    Player key) follows the queue: it is replaced along with the Player. That page meant "the video
 *    I am watching", so after a Next it would describe the wrong one — and keeping it would add one
 *    Detail per play-and-minimize cycle.
 *  - **Anything else** — a library or category list, or a Detail the user was only reading when they
 *    reopened the player from the bar or the notification — is kept, and the Player alone is
 *    replaced. The list keeps its scroll position one Back further down.
 *
 * The new Detail is an ordinary one and carries no list: while the video plays, its Play attaches to
 * the running session and the queue goes on untouched; once playback has stopped, Play starts that
 * video alone, as from any Detail. It is never left directly on top of a Detail of the same video:
 * two adjacent equal keys collide in the saveable-state and ViewModel stores (see `push` in
 * MainActivity), so the existing one is landed on instead. A stack whose top is not a Player is
 * returned unchanged.
 */
fun minimizedBackStack(stack: List<NavKey>, nowPlayingId: String): List<NavKey> {
    val player = stack.lastOrNull() as? AppNavKey.Player ?: return stack
    val below = stack.dropLast(1)
    val under = below.lastOrNull()
    if (under.isDetailOf(nowPlayingId)) return below
    val kept = if (under.isDetailOf(player.youtubeId)) below.dropLast(1) else below
    if (kept.lastOrNull().isDetailOf(nowPlayingId)) return kept
    return kept + AppNavKey.Detail(nowPlayingId)
}

private fun NavKey?.isDetailOf(youtubeId: String): Boolean =
    this is AppNavKey.Detail && this.youtubeId == youtubeId
