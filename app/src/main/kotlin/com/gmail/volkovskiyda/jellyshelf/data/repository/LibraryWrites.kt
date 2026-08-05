package com.gmail.volkovskiyda.jellyshelf.data.repository

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * The coordination state every library-row writer shares, created once per
 * [DefaultLibraryRepository] and handed whole to each sub-repository split out of it
 * ([PlaystateWriter] today). One shared instance is the contract: a delegate holding its own
 * copy would serialize against nobody, and sync would quietly revert watch state whose stamps
 * it never saw.
 */
internal class LibraryWrites {
    /**
     * Serializes every read-modify-write of library rows — sync (manual or from the periodic
     * worker), per-video metadata application and watch-state updates — so concurrent writers
     * can't clobber each other's changes with stale snapshots.
     */
    val mutex = Mutex()

    /**
     * When this process last wrote a video's watch state locally. Sync's Jellyfin snapshot is
     * taken before it acquires [mutex], so a local write that lands during the (possibly
     * multi-second) fetch would otherwise be reverted to the server's pre-write state; the
     * merge keeps the local values whenever this stamp postdates the fetch start.
     */
    val watchStamps = ConcurrentHashMap<String, Long>()
}
