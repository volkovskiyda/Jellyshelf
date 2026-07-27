package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.core.content.edit
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode

/**
 * Synchronously readable mirror of the persisted theme mode.
 *
 * It exists for one moment DataStore cannot serve: the activity has to colour its window before
 * the first frame, and the settings read that knows the user's choice lands roughly a second later
 * on a cold start — long enough that a window following the *system* theme is plainly visible
 * behind a light-forced app. DataStore remains the source of truth; this is a write-through cache
 * whose only reader is that pre-content window.
 *
 * SharedPreferences deliberately, not a second DataStore: answering without suspending is the
 * entire reason it is here.
 */
class ThemeModeCache(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("theme_startup", Context.MODE_PRIVATE)

    /** The last mode seen, or [ThemeMode.AUTO] when nothing has been stored yet. */
    fun peek(): ThemeMode = ThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, null))

    fun store(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.storageValue) }
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
    }
}
