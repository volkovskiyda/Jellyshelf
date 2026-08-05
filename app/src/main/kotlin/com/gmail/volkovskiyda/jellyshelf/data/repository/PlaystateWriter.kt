package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.VideoDao
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_ITEM_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlayMethod
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.repository.PlaystateRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant

/** Shared logcat tag for the external-player / playstate flow: `adb logcat -s Playback`. Kept in
 *  the data layer so it doesn't depend on the Android-heavy `util.Playback`. */
private const val PLAYBACK_TAG = "Playback"

/**
 * Every watch-state write in the app — the manual toggle, the in-app player's session reports,
 * the stop handling both players share, and the periodic local position save — implemented apart
 * from [DefaultLibraryRepository] and composed back into it by delegation.
 *
 * Not a standalone unit: it runs on the repository's own [scope] and takes the repository's
 * [LibraryWrites] — the row-write mutex every library writer serializes on, and the watch-write
 * stamps sync's merge reads to keep local state it would otherwise revert. Constructing it with
 * instances of its own would silently break both contracts.
 */
internal class PlaystateWriter(
    private val videoDao: VideoDao,
    private val jellyfin: JellyfinDataSource,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val writes: LibraryWrites,
) : PlaystateRepository {

    /**
     * Serializes server playstate writes. Each send re-reads the row's current local state under
     * this lock, so rapid toggles can't commit out of order on the server — the last send always
     * carries the newest local state, whatever order the earlier ones landed in.
     */
    private val playstateMutex = Mutex()

    /**
     * Toggles [youtubeId]'s watch state locally and mirrors it to Jellyfin. Returns false when
     * the server write failed — the local state stays, but the next sync may revert it to the
     * server's value, so callers should tell the user.
     */
    override suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean {
        writes.mutex.withLock {
            val v = videoDao.get(youtubeId) ?: return false
            videoDao.updateWatchState(youtubeId, played, if (played) v.playbackPositionTicks else 0L)
            writes.watchStamps[youtubeId] = System.currentTimeMillis()
        }
        val s = settings.snapshot()
        return runCatchingCancellable {
            playstateMutex.withLock {
                // Re-read at send time: if another toggle landed while this one waited for the
                // lock, send the newer state — the server then converges on the latest local
                // value regardless of how the calls interleaved.
                val latest = videoDao.get(youtubeId) ?: return false
                val itemId = latest.jellyfinItemId
                if (s.isConnected && itemId != null) {
                    jellyfin.setPlayed(s.serverUrl, s.credential, s.userId, itemId, latest.played)
                }
            }
            true
        }.getOrElse { e ->
            Timber.tag(PLAYBACK_TAG).w(e, "setPlayed: server write failed for youtubeId=$youtubeId")
            false
        }
    }

    /**
     * Record where playback stopped: persist the resume position locally and mirror the result to
     * Jellyfin.
     *
     * - Finished (played to the end, or stopped within a few seconds of it): mark played through
     *   the dedicated /PlayedItems endpoint — the same one the manual "watched" toggle uses. That
     *   is what increments PlayCount, stamps LastPlayedDate and lands the item in the server's
     *   watch history. Writing UserData with Played=true does *not* reliably register a play.
     * - Stopped partway from the in-app player ([liveSession]): close the Jellyfin session with the
     *   final position and let the server threshold it, like every progress report before it. A
     *   direct UserData write here would carry `Played=false` over a mark the server made itself
     *   moments earlier — un-watching the video the user just watched.
     * - Stopped partway from an external player: write the resume position to the user's item data,
     *   which is what surfaces the item in "Continue Watching". A handed-off player reports nothing
     *   while it runs, so there is no session for the server to threshold — only this one result.
     *
     * Best-effort — network failures are swallowed so local state still updates.
     */
    override fun reportPlaybackStopped(
        youtubeId: String,
        positionMs: Long,
        completed: Boolean,
        playSessionId: String?,
    ) {
        // Fire-and-forget on the repository's own scope: the screen that launched the external
        // player may be gone (back press, rotation) before the local write and the network
        // report finish, and losing the resume position is not acceptable.
        scope.launch { onPlaybackStopped(youtubeId, positionMs, completed, playSessionId) }
    }

    override fun reportPlaybackStarted(
        youtubeId: String,
        positionMs: Long,
        playSessionId: String?,
        playMethod: PlayMethod,
    ) {
        reportSession(youtubeId, what = "start") { s, itemId ->
            jellyfin.reportPlaybackStart(
                serverUrl = s.serverUrl,
                credential = s.credential,
                itemId = itemId,
                positionTicks = millisToTicks(positionMs),
                playSessionId = playSessionId,
                playMethod = playMethod,
            )
        }
    }

    override fun reportPlaybackProgress(
        youtubeId: String,
        positionMs: Long,
        isPaused: Boolean,
        playSessionId: String?,
        playMethod: PlayMethod,
    ) {
        reportSession(youtubeId, what = "progress") { s, itemId ->
            jellyfin.reportPlaybackProgress(
                serverUrl = s.serverUrl,
                credential = s.credential,
                itemId = itemId,
                positionTicks = millisToTicks(positionMs),
                isPaused = isPaused,
                playSessionId = playSessionId,
                playMethod = playMethod,
            )
        }
    }

    /**
     * Closes an open server session and nothing else — for the stop that decided the video was
     * barely touched and left every stored position, local and remote, exactly as it found it.
     *
     * It goes through the same gates as the reports that opened the session ([reportSession]),
     * which is what keeps a demo row from ever reaching the network.
     */
    private fun closeSession(youtubeId: String, positionMs: Long, playSessionId: String) {
        reportSession(youtubeId, what = "stop (below the resume bar)") { s, itemId ->
            jellyfin.reportPlaybackSessionStopped(
                serverUrl = s.serverUrl,
                credential = s.credential,
                itemId = itemId,
                positionTicks = millisToTicks(positionMs),
                playSessionId = playSessionId,
            )
        }
    }

    /**
     * The shell both in-flight session reports share: fire-and-forget on [scope], serialized
     * behind [playstateMutex] so a report can't overtake the stop report or a manual toggle, and
     * best-effort — a dropped start or progress report costs at most one interval of server-side
     * accuracy, so failures are logged and swallowed like every other playstate write.
     *
     * Nothing is written locally: [savePlaybackPosition] owns the local position, and the server
     * owns the watched verdict these reports feed.
     */
    private fun reportSession(
        youtubeId: String,
        what: String,
        send: suspend (Settings, String) -> Unit,
    ) {
        scope.launch {
            val s = settings.snapshot()
            if (!s.isConnected) return@launch
            runCatchingCancellable {
                playstateMutex.withLock {
                    // Re-read under the lock, as every playstate send does. Demo rows are skipped by
                    // their sentinel item id rather than the demoMode flag: a row can outlive the
                    // flag (see PlaybackService.resolve), and a demo video has no server behind it.
                    val itemId = videoDao.get(youtubeId)?.jellyfinItemId ?: return@withLock
                    if (itemId == DEMO_ITEM_ID) return@withLock
                    send(s, itemId)
                }
            }.onFailure { e ->
                Timber.tag(PLAYBACK_TAG).w(e, "session $what report failed for youtubeId=$youtubeId")
            }
        }
    }

    override fun savePlaybackPosition(youtubeId: String, positionMs: Long) {
        // Fire-and-forget like reportPlaybackStopped: the playback service calls this every few
        // seconds and must never wait on Room. The DAO query itself skips played rows.
        val positionTicks = millisToTicks(positionMs)
        scope.launch {
            writes.mutex.withLock {
                // Ticks only start being recorded once the video is genuinely under way
                // ([isWorthResuming]). Gating the stop report alone would not be enough — this
                // runs every few seconds regardless, so the trivial position would already be on
                // disk, and both the resume seed and the next sync would treat it as real.
                val v = videoDao.get(youtubeId) ?: return@withLock
                if (!isWorthResuming(positionTicks, v.durationSeconds)) return@withLock
                videoDao.updatePlaybackPosition(youtubeId, positionTicks)
                // Stamped like onPlaybackStopped's write: a sync whose server snapshot predates
                // this save must keep the local position, not revert it.
                writes.watchStamps[youtubeId] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun onPlaybackStopped(
        youtubeId: String,
        positionMs: Long,
        completed: Boolean,
        playSessionId: String?,
    ) {
        val positionTicks = millisToTicks(positionMs)
        Timber.tag(PLAYBACK_TAG).d(
            "onPlaybackStopped: youtubeId=$youtubeId positionMs=$positionMs " +
                "positionTicks=$positionTicks completed=$completed",
        )
        var finished = completed
        val video = writes.mutex.withLock {
            val v = videoDao.get(youtubeId) ?: run {
                Timber.tag(PLAYBACK_TAG).w(
                    "onPlaybackStopped: no local row for youtubeId=$youtubeId; nothing to report",
                )
                return
            }
            finished = isFinishedStop(completed, positionTicks, v.durationSeconds)
            // Barely into it and not finished — stepping past this video rather than watching it.
            // Nothing is written at all, locally or to the server: the position it already holds
            // is a better answer than the one this stop would replace it with.
            if (!finished && !isWorthResuming(positionTicks, v.durationSeconds)) {
                Timber.tag(PLAYBACK_TAG).d(
                    "onPlaybackStopped: only ${ticksToSeconds(positionTicks)}s into " +
                        "${v.durationSeconds}s; leaving the stored position alone",
                )
                // Nothing is recorded — but a session the periodic ticks opened still has to be
                // closed, or the server shows this video as playing until it times the session out.
                // The position it carries is where playback really stopped, which is the same low
                // value the progress reports were already sending; the stored one stays untouched.
                playSessionId?.let { closeSession(youtubeId, positionMs, it) }
                return
            }
            Timber.tag(PLAYBACK_TAG).d(
                "onPlaybackStopped: durationSeconds=${v.durationSeconds} finished=$finished -> " +
                    "local write played=$finished position=${if (finished) 0L else positionTicks}",
            )
            videoDao.updateWatchState(youtubeId, finished, if (finished) 0L else positionTicks)
            writes.watchStamps[youtubeId] = System.currentTimeMillis()
            v
        }

        val s = settings.snapshot()
        val itemId = video.jellyfinItemId
        if (!s.isConnected || itemId == null) {
            Timber.tag(PLAYBACK_TAG).d(
                "onPlaybackStopped: skipping server report (connected=${s.isConnected} " +
                    "itemId=$itemId)",
            )
            return
        }
        // Best-effort: the local resume position is already saved, so a failed server
        // write is swallowed.
        reportPlaybackToServer(youtubeId, itemId, s, playSessionId)
    }

    private suspend fun reportPlaybackToServer(
        youtubeId: String,
        itemId: String,
        s: Settings,
        playSessionId: String?,
    ) {
        // Having a session id *is* what makes this an in-app stop: it is minted when the in-app
        // player opens the server session, and an external handoff never has one.
        val liveSession = playSessionId != null
        runCatchingCancellable {
            playstateMutex.withLock {
                // Re-read at send time (see setPlayed): a toggle that landed while this
                // report waited for the lock must not be overwritten with older state.
                val latest = videoDao.get(youtubeId) ?: return
                // The in-app player reported this session all along, so it ends the way it ran:
                // one more position for the server to threshold, exactly as it thresholded every
                // progress report. That catches the case no progress report can — seeking past the
                // watched mark and closing between two ticks — and, unlike the direct write below,
                // it can never say Played=false over a verdict the server reached mid-play.
                //
                // A finished stop carries position 0 (the local write cleared it just above), which
                // is how the server records a play that ran to the end rather than a resume point.
                // The one case with nothing to say is an unplayed row sitting at 0 — only reachable
                // if an "unwatched" toggle landed while this report waited for the lock, and a
                // zero-position stop would mark it played right back.
                val sessionClosed = liveSession && (latest.played || latest.playbackPositionTicks > 0)
                if (sessionClosed) {
                    Timber.tag(PLAYBACK_TAG).d(
                        "onPlaybackStopped: closing the Jellyfin session for itemId=$itemId " +
                            "positionTicks=${latest.playbackPositionTicks} played=${latest.played}",
                    )
                    jellyfin.reportPlaybackSessionStopped(
                        serverUrl = s.serverUrl,
                        credential = s.credential,
                        itemId = itemId,
                        positionTicks = latest.playbackPositionTicks,
                        playSessionId = playSessionId,
                    )
                }
                if (latest.played) {
                    // Finished — record the play in Jellyfin's watch history via the endpoint
                    // that actually marks items played (PlayCount++, LastPlayedDate, resume cleared).
                    // Kept for the session path too: the stop report above marks the item played,
                    // but only this endpoint guarantees the history entry however odd the runtime
                    // metadata is — the same belt and braces the official Android client uses.
                    Timber.tag(PLAYBACK_TAG).d("onPlaybackStopped: marking played on Jellyfin itemId=$itemId")
                    jellyfin.setPlayed(s.serverUrl, s.credential, s.userId, itemId, played = true)
                } else if (!liveSession) {
                    // An external player stopped partway — persist the resume position for
                    // "Continue Watching". It reported nothing while it ran, so this result is all
                    // the server ever hears about the playback: there is no session to threshold.
                    Timber.tag(PLAYBACK_TAG).d(
                        "onPlaybackStopped: writing resume position to Jellyfin itemId=$itemId " +
                            "positionTicks=${latest.playbackPositionTicks}",
                    )
                    jellyfin.updatePlaybackState(
                        serverUrl = s.serverUrl,
                        credential = s.credential,
                        userId = s.userId,
                        itemId = itemId,
                        positionTicks = latest.playbackPositionTicks,
                        played = false,
                        lastPlayedDate = Instant.now().toString(),
                    )
                }
                // Only a partway stop needs asking: a finished one already wrote played, position 0
                // locally, which is exactly what the server made of it.
                if (sessionClosed && !latest.played) mirrorServerWatchState(youtubeId, itemId, s, latest)
            }
            Timber.tag(PLAYBACK_TAG).d("onPlaybackStopped: server report succeeded for itemId=$itemId")
        }.onFailure { e ->
            Timber.tag(PLAYBACK_TAG).w(e, "onPlaybackStopped: server report failed for itemId=$itemId")
        }
    }

    /**
     * Reads the server's verdict back after an in-app stop and mirrors it into the local row.
     *
     * The app's own rule only calls a stop finished within seconds of the end, so after a partway
     * stop the server routinely knows better: it marks anything past its watched threshold (90% by
     * default), and short items outright. Without this the library would keep offering the video
     * under "Continue watching" — for hours, until the next sync corrected it.
     *
     * The play count comes along with it, which matters more than freshness: the stamp below makes
     * the next sync keep this row's watch state wholesale ([LibraryWrites.watchStamps], and
     * `VideoMerge.resolveWatchState` retains the triple together), so a count left behind here
     * would be preserved as though it were the truth rather than corrected.
     *
     * That stamp is also what makes the write safe against a sync already in flight: that sync's
     * server snapshot predates this write, and the merge keeps local values stamped after its
     * fetch began.
     */
    private suspend fun mirrorServerWatchState(
        youtubeId: String,
        itemId: String,
        s: Settings,
        reported: VideoEntity,
    ) {
        runCatchingCancellable {
            val userData = jellyfin.getItem(s.serverUrl, s.credential, s.userId, itemId).userData ?: return
            writes.mutex.withLock {
                val current = videoDao.get(youtubeId) ?: return@withLock
                // Mirror only onto the state this report was made from. Anything else means the row
                // moved on while the fetch was in flight — a manual toggle, most likely — and that
                // is newer than a verdict the server reached before it heard about it.
                if (current.played != reported.played ||
                    current.playbackPositionTicks != reported.playbackPositionTicks
                ) {
                    return@withLock
                }
                val played = userData.played
                val positionTicks = userData.playbackPositionTicks
                val playCount = userData.playCount
                videoDao.updateServerWatchState(
                    youtubeId = youtubeId,
                    played = played,
                    positionTicks = positionTicks,
                    playCount = playCount,
                )
                writes.watchStamps[youtubeId] = System.currentTimeMillis()
                Timber.tag(PLAYBACK_TAG).d(
                    "onPlaybackStopped: mirrored the server's verdict for itemId=$itemId " +
                        "played=$played positionTicks=$positionTicks playCount=$playCount",
                )
            }
        }.onFailure { e ->
            Timber.tag(PLAYBACK_TAG).w(e, "onPlaybackStopped: mirror fetch failed for itemId=$itemId")
        }
    }
}
