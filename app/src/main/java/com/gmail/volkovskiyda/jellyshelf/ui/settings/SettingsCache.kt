package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto

/**
 * Process-lifetime Settings cache, owned by the app container. Tab switches clear the Settings
 * ViewModel, and without this every visit to the tab would re-query the server for its users.
 * Entries are keyed by the server URL they came from, so a changed URL never serves another
 * server's users.
 */
class SettingsCache {
    private data class Entry(val serverUrl: String, val users: List<UserDto>)

    @Volatile
    private var entry: Entry? = null

    fun usersFor(serverUrl: String): List<UserDto>? =
        entry?.takeIf { it.serverUrl == serverUrl }?.users

    fun store(serverUrl: String, users: List<UserDto>) {
        entry = Entry(serverUrl, users)
    }
}
