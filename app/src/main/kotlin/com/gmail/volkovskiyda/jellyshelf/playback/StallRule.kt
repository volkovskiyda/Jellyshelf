package com.gmail.volkovskiyda.jellyshelf.playback

/**
 * When a buffering player has been silent long enough to count as stuck rather than slow.
 *
 * **Why a rule and not just a timeout.** "Buffering for fifteen seconds" is the wrong condition: a
 * high-bitrate video starting over a poor mobile connection can legitimately buffer for longer than
 * that, and killing it would turn a slow start into a failed one. What separates the two is whether
 * anything is *arriving*. A stream that is merely slow keeps transferring bytes; a stream whose
 * socket has gone means the loader sits there with nothing coming back. So the clock here is reset
 * by bytes, not by buffering, and the window it measures is one of silence.
 *
 * **One recovery per item, deliberately.** Recovering means tearing the player's loading state down
 * and building it again, and a recovery that does not help would otherwise do that every fifteen
 * seconds for as long as the video was open. After one attempt this rule goes quiet and leaves the
 * outcome to ExoPlayer's own retry and error path, which is the thing designed to give up. The
 * budget returns when the queue moves to another video — a new item is a new problem.
 *
 * That also means a *second*, unrelated stall on the same item is not covered. That is the intended
 * trade: the loop guard is worth more than the second chance, because the failure it prevents
 * (endless silent re-preparing) is worse than the one it allows (a spinner, which is where this
 * started and which ExoPlayer will eventually turn into an error).
 *
 * Pure and single-threaded, in the shape of [WatchStateTracker] and for the same reason: the
 * decision is the part worth testing, while the behaviour it drives — a player torn down and rebuilt
 * mid-stream — needs a device, a server and a dead socket to observe. [StallWatchdog] is the half
 * that talks to media3.
 *
 * Every timestamp passed in must come from the same monotonic clock; see [StallWatchdog].
 */
internal class StallRule(private val timeoutMs: Long = STALL_TIMEOUT_MS) {

    /**
     * When the current stall window opened, or null when playback is not waiting on anything.
     *
     * "Buffering" here means buffering *while the user wants playback* — a paused player buffers
     * nothing and a stopped one is not waiting. Folding `playWhenReady` in is the caller's job; see
     * [StallWatchdog.reevaluate].
     */
    private var bufferingSinceMs: Long? = null

    /** When bytes last arrived, on any stream. Zero until the first byte of the process. */
    private var lastBytesMs = 0L

    /** Spent by a recovery, returned when the queue moves on. */
    private var recoveredThisItem = false

    /**
     * Playback started waiting for data. Repeated calls while already waiting keep the original
     * timestamp, so a run of state changes that all mean "still buffering" cannot keep pushing the
     * deadline out.
     */
    fun onBuffering(nowMs: Long) {
        if (bufferingSinceMs == null) bufferingSinceMs = nowMs
    }

    /** Playback is no longer waiting — ready, ended, idle, or paused by the user. */
    fun onNotBuffering() {
        bufferingSinceMs = null
    }

    /**
     * Bytes arrived. Guarded against going backwards because this is fed from a value written by
     * loader threads and read here, and a stale read must not be able to pull the deadline in.
     */
    fun onBytes(nowMs: Long) {
        if (nowMs > lastBytesMs) lastBytesMs = nowMs
    }

    /** The queue moved to another video: no stall in progress, and the recovery budget is back. */
    fun onItemChanged() {
        bufferingSinceMs = null
        recoveredThisItem = false
    }

    /**
     * Whether to recover now — true at most once per stall episode, and at most once per item.
     *
     * The window is measured from the later of "started waiting" and "last byte", which is what
     * makes this a silence timeout rather than a buffering timeout: bytes arriving mid-wait push the
     * deadline out, and a wait that begins after the last byte is timed from the wait.
     */
    fun tick(nowMs: Long): Boolean {
        val bufferingSince = bufferingSinceMs ?: return false
        if (recoveredThisItem) return false
        val silentSinceMs = maxOf(bufferingSince, lastBytesMs)
        if (nowMs - silentSinceMs < timeoutMs) return false
        recoveredThisItem = true
        return true
    }
}

/**
 * How long a buffering player may receive nothing at all before it is treated as stuck.
 *
 * Fifteen seconds is well past any hitch a healthy stream produces — a rebuffer refills from a
 * server that is answering, and the first bytes land in well under a second on a connection that
 * works — and well short of the half-minute or more that ExoPlayer's own retry ladder takes to
 * surface an error, which is the wait this exists to cut short.
 */
internal const val STALL_TIMEOUT_MS = 15_000L
