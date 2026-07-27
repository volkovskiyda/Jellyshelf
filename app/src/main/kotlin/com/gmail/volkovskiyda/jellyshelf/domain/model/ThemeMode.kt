package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * The user's theme override. [AUTO] follows the system and is the fresh-install default, so an
 * install that never touches the setting behaves exactly as the app did before it existed.
 * [storageValue] is what DataStore persists — stable across renames of the enum constants.
 */
enum class ThemeMode(val storageValue: String) {
    LIGHT("light"),
    AUTO("auto"),
    DARK("dark"),
    ;

    companion object {
        /** Unknown or absent stored values degrade to [AUTO] rather than failing the read. */
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: AUTO
    }
}

/**
 * The persisted theme selection plus the direction the *next* tap moves in. Tapping the switch
 * walks a ping-pong: light → auto → dark → auto → light → … so the two ends bounce and auto is
 * crossed in both directions.
 *
 * [towardDark] only decides anything at [ThemeMode.AUTO] — the ends have nowhere else to go — but
 * it is persisted alongside the mode because otherwise a process restart landing on auto would not
 * know which way the user was heading, and the sequence would jump.
 */
data class ThemeState(
    val mode: ThemeMode = ThemeMode.AUTO,
    val towardDark: Boolean = true,
) {
    /** The state one tap ahead. */
    fun next(): ThemeState = when {
        mode == ThemeMode.LIGHT -> ThemeState(ThemeMode.AUTO, towardDark = true)
        mode == ThemeMode.DARK -> ThemeState(ThemeMode.AUTO, towardDark = false)
        towardDark -> ThemeState(ThemeMode.DARK, towardDark = false)
        else -> ThemeState(ThemeMode.LIGHT, towardDark = true)
    }
}
