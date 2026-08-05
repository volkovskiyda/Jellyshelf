package com.gmail.volkovskiyda.jellyshelf.playback

import java.util.UUID

/** What the service should do about watch state; null (or an empty list) wherever it is "nothing". */
internal sealed interface WatchAction {
    /**
     * A full stop report to the server: this video is finished with, for now or for good.
     * [playSessionId] is non-null exactly when a server session was open behind it, which is what
     * decides how the position is sent — see
     * [com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository].
     */
    data class Report(
        val youtubeId: String,
        val positionMs: Long,
        val completed: Boolean,
        val playSessionId: String?,
    ) : WatchAction

    /** Opens the server's playback session, once this video is genuinely being watched. */
    data class SessionStart(
        val youtubeId: String,
        val positionMs: Long,
        val playSessionId: String,
    ) : WatchAction

    /** An in-flight position for the open session — the server's chance to mark it watched mid-play. */
    data class Progress(
        val youtubeId: String,
        val positionMs: Long,
        val isPaused: Boolean,
        val playSessionId: String,
    ) : WatchAction

    /** A local-only position save, so process death cannot lose where the user got to. */
    data class Save(val youtubeId: String, val positionMs: Long) : WatchAction
}

/**
 * The rules behind [PlaybackService]'s watch-state reporting, separated from the media3 plumbing
 * that feeds them: which video is active, where it had got to, whether its completion has already
 * been reported, and what the server's playback session has been told.
 *
 * Pulled out of the listener so the rules can be tested without standing up a
 * `MediaSessionService` — they are the part with decisions in it, and those decisions (report
 * exactly once, open the session once and only for a video actually being watched, never let
 * another item's position clobber the active one) are the kind that fail silently by writing a
 * wrong number rather than by crashing.
 *
 * Single-threaded by design: the service's player runs on its main thread and every call arrives
 * from there, which is what makes plain vars safe here.
 *
 * [newSessionId] mints the id each server session is reported under; it is a parameter purely so
 * tests can pin what is otherwise a random UUID.
 */
internal class WatchStateTracker(
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {

    var activeMediaId: String? = null
        private set

    /**
     * Where the active video had reached, as last observed. Kept up to date by pauses, periodic
     * saves and discontinuities so a stop report carries a real position rather than a stale one.
     */
    var lastPositionMs: Long = 0L
        private set

    /** Set once the active video has been reported complete, so nothing reports it again. */
    var completionReported: Boolean = false
        private set

    /**
     * The id every report about the active video's server session carries, or null while it has
     * none — which doubles as "has the server been told this video is playing?".
     *
     * Minted by the video's first periodic tick and cleared only when the active item changes: a
     * pause, a replay or an ended-then-resumed video is the same session continuing, and a second
     * start report would clear the server's played flag and count another play.
     */
    var playSessionId: String? = null
        private set

    /**
     * The active item changed. Reports the outgoing one — as *completed* only on an auto-advance,
     * since any other reason is a replacement mid-way (a new video picked from Detail).
     *
     * Silent when that video already reported its completion, for the same reason [onDestroy] is:
     * the last video of a queue ends without transitioning anywhere, so it is still active when
     * the user leaves and clears the queue. A second report would carry `completed = false` at a
     * position up to a save interval short of the end, and un-watch what was just watched.
     */
    fun onItemChanged(newMediaId: String?, autoAdvance: Boolean): WatchAction.Report? {
        val previous = activeMediaId
        // Read before the reset below: the report belongs to the video being left, so it carries
        // that video's session flag, not the incoming one's.
        val report = if (previous != null && previous != newMediaId && !completionReported) {
            WatchAction.Report(previous, lastPositionMs, completed = autoAdvance, playSessionId = playSessionId)
        } else {
            null
        }
        activeMediaId = newMediaId
        lastPositionMs = 0L
        completionReported = false
        playSessionId = null
        return report
    }

    /**
     * A jump in the timeline.
     *
     * Anchored on media ids rather than on the reason code, because the order between this and the
     * item transition is not part of the `Player` contract. Another item's position must never
     * clobber the active one's — replacing a playing video would otherwise report position 0 for
     * it and wipe its saved resume spot. Leaving the active item captures its exact final position
     * (better than the up-to-10-seconds-stale periodic value); movement within it tracks the new
     * side.
     */
    fun onPositionDiscontinuity(
        oldMediaId: String?,
        oldPositionMs: Long,
        newMediaId: String?,
        newPositionMs: Long,
    ) {
        val leavingActive = oldMediaId == activeMediaId && newMediaId != activeMediaId
        if (leavingActive) {
            lastPositionMs = oldPositionMs
        } else if (newMediaId == activeMediaId) {
            lastPositionMs = newPositionMs
        }
    }

    /**
     * Playback started or resumed; any earlier completion no longer stands.
     *
     * A resume the server knows about is passed on as a playing progress report — the mirror of the
     * paused one [onPaused] sends. Without it the server keeps calling a video paused until the next
     * periodic tick, a whole save interval after it visibly started moving again, and a resume the
     * user immediately leaves is never corrected at all.
     *
     * Silent when no session is open: pressing play must not be what opens one, or the video tapped
     * past a moment later comes back unwatched with a play counted against it. Opening stays the
     * first periodic tick's job alone ([onPeriodicTick]).
     */
    fun onPlaying(positionMs: Long): WatchAction.Progress? {
        completionReported = false
        val id = activeMediaId ?: return null
        val session = playSessionId ?: return null
        lastPositionMs = positionMs
        return WatchAction.Progress(id, positionMs, isPaused = false, playSessionId = session)
    }

    /**
     * Playback stopped being active. Saves only on a genuine pause — [ready] is the player being
     * `STATE_READY` — so that a swipe-away straight afterwards loses nothing. Buffering is not a
     * pause, and an ended video has its own full report.
     *
     * A pause the server knows about is passed on as a paused progress report, so its dashboard
     * stops advancing. Nothing periodic follows: reporting is for playback that is playing.
     */
    fun onPaused(positionMs: Long, ready: Boolean): List<WatchAction> {
        val id = activeMediaId
        if (!ready || id == null) return emptyList()
        lastPositionMs = positionMs
        return buildList {
            add(WatchAction.Save(id, positionMs))
            playSessionId?.let { add(WatchAction.Progress(id, positionMs, isPaused = true, playSessionId = it)) }
        }
    }

    /**
     * The periodic tick while *paused*: the same position again, so the server's session doesn't
     * age out of its dashboard while a video sits paused. Nothing local — the position has not
     * moved since [onPaused] saved it — and nothing at all unless a session is actually open.
     *
     * A seek while paused does move it, and the current position is what gets reported, so the
     * keep-alive doubles as the only way the server hears about that until playback resumes.
     */
    fun onPausedKeepAlive(positionMs: Long): WatchAction.Progress? {
        val id = activeMediaId ?: return null
        val session = playSessionId ?: return null
        lastPositionMs = positionMs
        return WatchAction.Progress(id, positionMs, isPaused = true, playSessionId = session)
    }

    /**
     * The periodic tick while playing: remember the position, persist it locally, and tell the
     * server — the first tick opens the playback session, every later one reports progress into it.
     *
     * Opening on the first tick rather than the moment the queue reaches a video is what keeps
     * stepping through a queue silent. The server clears the played flag and counts a play when a
     * session opens, so a video passed over in a second would come back unwatched with a play to
     * its name.
     *
     * Exactly one session action per tick, never a start and a progress together: the two are
     * dispatched as separate fire-and-forget sends, and a progress report overtaking the start it
     * belongs to would be reported against no session at all.
     */
    fun onPeriodicTick(positionMs: Long): List<WatchAction> {
        val id = activeMediaId ?: return emptyList()
        lastPositionMs = positionMs
        val open = playSessionId
        val session = if (open != null) {
            WatchAction.Progress(id, positionMs, isPaused = false, playSessionId = open)
        } else {
            val opened = newSessionId()
            playSessionId = opened
            WatchAction.SessionStart(id, positionMs, playSessionId = opened)
        }
        return listOf(session, WatchAction.Save(id, positionMs))
    }

    /**
     * The video played to its end. Reported at [durationMs] when the duration is known, so the
     * server records a full watch rather than wherever the last tick happened to land.
     */
    fun onEnded(durationMs: Long?): WatchAction.Report? {
        val id = activeMediaId ?: return null
        completionReported = true
        return WatchAction.Report(
            id,
            durationMs ?: lastPositionMs,
            completed = true,
            playSessionId = playSessionId,
        )
    }

    /**
     * The service is going away (swipe from recents, system stop). Files a last partway report —
     * unless the active video already reported its completion, which this would contradict.
     */
    fun onDestroy(currentPositionMs: Long): WatchAction.Report? {
        val id = activeMediaId
        if (id == null || completionReported) return null
        return WatchAction.Report(id, currentPositionMs, completed = false, playSessionId = playSessionId)
    }
}
