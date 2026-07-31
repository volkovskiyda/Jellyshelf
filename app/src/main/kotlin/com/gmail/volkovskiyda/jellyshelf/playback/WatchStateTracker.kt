package com.gmail.volkovskiyda.jellyshelf.playback

/** What the service should do about watch state; null wherever the answer is "nothing". */
internal sealed interface WatchAction {
    /** A full stop report to the server: this video is finished with, for now or for good. */
    data class Report(val youtubeId: String, val positionMs: Long, val completed: Boolean) : WatchAction

    /** A local-only position save, so process death cannot lose where the user got to. */
    data class Save(val youtubeId: String, val positionMs: Long) : WatchAction
}

/**
 * The rules behind [PlaybackService]'s watch-state reporting, separated from the media3 plumbing
 * that feeds them: which video is active, where it had got to, and whether its completion has
 * already been reported.
 *
 * Pulled out of the listener so the rules can be tested without standing up a
 * `MediaSessionService` — they are the part with decisions in it, and two of those decisions
 * (report exactly once, never let another item's position clobber the active one) are the kind
 * that fail silently by writing a wrong number rather than by crashing.
 *
 * Single-threaded by design: the service's player runs on its main thread and every call arrives
 * from there, which is what makes plain vars safe here.
 */
internal class WatchStateTracker {

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
     * The active item changed. Reports the outgoing one — as *completed* only on an auto-advance,
     * since any other reason is a replacement mid-way (a new video picked from Detail).
     */
    fun onItemChanged(newMediaId: String?, autoAdvance: Boolean): WatchAction.Report? {
        val previous = activeMediaId
        val report = if (previous != null && previous != newMediaId) {
            WatchAction.Report(previous, lastPositionMs, completed = autoAdvance)
        } else {
            null
        }
        activeMediaId = newMediaId
        lastPositionMs = 0L
        completionReported = false
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

    /** Playback started or resumed; any earlier completion no longer stands. */
    fun onPlaying() {
        completionReported = false
    }

    /**
     * Playback stopped being active. Saves only on a genuine pause — [ready] is the player being
     * `STATE_READY` — so that a swipe-away straight afterwards loses nothing. Buffering is not a
     * pause, and an ended video has its own full report.
     */
    fun onPaused(positionMs: Long, ready: Boolean): WatchAction.Save? {
        val id = activeMediaId
        if (!ready || id == null) return null
        lastPositionMs = positionMs
        return WatchAction.Save(id, positionMs)
    }

    /** The periodic tick while playing: remember the position and persist it locally. */
    fun onPeriodicSave(positionMs: Long): WatchAction.Save? {
        val id = activeMediaId ?: return null
        lastPositionMs = positionMs
        return WatchAction.Save(id, positionMs)
    }

    /**
     * The video played to its end. Reported at [durationMs] when the duration is known, so the
     * server records a full watch rather than wherever the last tick happened to land.
     */
    fun onEnded(durationMs: Long?): WatchAction.Report? {
        val id = activeMediaId ?: return null
        completionReported = true
        return WatchAction.Report(id, durationMs ?: lastPositionMs, completed = true)
    }

    /**
     * The service is going away (swipe from recents, system stop). Files a last partway report —
     * unless the active video already reported its completion, which this would contradict.
     */
    fun onDestroy(currentPositionMs: Long): WatchAction.Report? {
        val id = activeMediaId
        if (id == null || completionReported) return null
        return WatchAction.Report(id, currentPositionMs, completed = false)
    }
}
