package com.gmail.volkovskiyda.jellyshelf.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Synchronously readable "is there anything to resume".
 *
 * The same shape, and for the same kind of reason, as
 * [ThemeModeCache][com.gmail.volkovskiyda.jellyshelf.data.repository.ThemeModeCache]: a moment
 * that has to be answered *before* a suspending read can land. Here it is
 * [MediaButtonGate.shouldStartForegroundService], which runs inside a broadcast on a process that
 * may have just been created, and has to decide there and then whether starting the playback
 * service is justified.
 *
 * Getting that decision wrong is not cosmetic. A media button arrives as `startForegroundService`,
 * and a service that never reaches `startForeground` — which is what happens when there turns out
 * to be nothing to play — is killed by the platform with
 * `ForegroundServiceDidNotStartInTimeException`. Declining to start it at all is the only way not
 * to be in that position.
 *
 * DataStore stays the source of truth: `lastPlayedVideoId` is what the resumption itself reads,
 * and this mirrors only the yes/no. SharedPreferences deliberately, not a second DataStore —
 * answering without suspending is the entire reason it exists.
 */
class ResumableCache(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("playback_resume", Context.MODE_PRIVATE)

    /** False until something has played, which is the safe answer: no start, so no crash. */
    fun peek(): Boolean = prefs.getBoolean(KEY_RESUMABLE, false)

    fun store(resumable: Boolean) {
        prefs.edit { putBoolean(KEY_RESUMABLE, resumable) }
    }

    /**
     * Lowers the flag whenever [nothingToResume] says the last-played video is gone. Never
     * returns; the application scope runs it for the life of the process.
     *
     * [PlaybackService] writes the flag on every item transition, which keeps it right while
     * something plays. It cannot notice what happens *between* playbacks, though: a sign-out
     * wipes the library, a sync drops a video deleted on the server, and the flag goes on
     * saying yes. The next headset press then starts the service in the foreground for a
     * resumption that fails, and the platform kills the app at the start deadline — the exact
     * outcome [MediaButtonGate] exists to prevent. The resumption's own `store(false)` heals
     * only the press after that one.
     *
     * Only ever lowers it. Raising stays with the transition, because a row that exists is not
     * proof that it can play: a half-cleared demo row resolves to nothing, and raising the flag
     * for it on every launch would undo the resumption's self-heal.
     */
    suspend fun clearWhenGone(lastPlayedId: Flow<String?>, videoExists: (String) -> Flow<Boolean>) {
        nothingToResume(lastPlayedId, videoExists).collect { store(false) }
    }

    private companion object {
        const val KEY_RESUMABLE = "resumable"
    }
}

/**
 * Emits each time "the last-played video is still in the library" turns false: no id (nothing
 * played since install, or an explicit stop cleared it), or a row that has gone. Separate from
 * [ResumableCache] so the rule is testable without SharedPreferences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun nothingToResume(
    lastPlayedId: Flow<String?>,
    videoExists: (String) -> Flow<Boolean>,
): Flow<Unit> = lastPlayedId
    .flatMapLatest { id -> if (id == null) flowOf(false) else videoExists(id) }
    .distinctUntilChanged()
    .filter { exists -> !exists }
    .map { }
