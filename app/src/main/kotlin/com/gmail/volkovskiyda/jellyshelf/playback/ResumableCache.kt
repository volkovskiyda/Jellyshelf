package com.gmail.volkovskiyda.jellyshelf.playback

import android.content.Context
import androidx.core.content.edit

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

    private companion object {
        const val KEY_RESUMABLE = "resumable"
    }
}
