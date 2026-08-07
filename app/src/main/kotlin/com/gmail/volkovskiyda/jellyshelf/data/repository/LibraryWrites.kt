package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * The coordination state every library-row writer shares, created once per
 * [DefaultLibraryRepository] and handed whole to each sub-repository split out of it
 * ([PlaystateWriter] today). One shared instance is the contract: a delegate holding its own
 * copy would serialize against nobody, and sync would quietly revert watch state whose stamps
 * it never saw.
 */
internal class LibraryWrites(private val time: TimeProvider) {
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

    /**
     * Records that this process just wrote [youtubeId]'s watch state.
     *
     * Here rather than at the four call sites in [PlaystateWriter] because the stamp and the map it
     * stamps have to agree on which clock they mean: [DefaultLibraryRepository] compares these
     * values against a fetch start it reads from the same [TimeProvider], and a stamp taken from a
     * different clock would compare as either always-newer or never-newer.
     */
    fun stamp(youtubeId: String) {
        watchStamps[youtubeId] = time.now()
    }
}
