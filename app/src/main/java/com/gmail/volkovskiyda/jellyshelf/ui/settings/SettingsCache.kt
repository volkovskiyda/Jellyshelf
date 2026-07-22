package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.domain.model.User

/**
 * Process-lifetime Settings cache. Tab switches clear the Settings ViewModel, and without this
 * every visit to the tab would re-query the server for its users. Entries are keyed by the server
 * URL they came from, so a changed URL never serves another server's users.
 */
class SettingsCache {
    private data class Entry(val serverUrl: String, val users: List<User>)

    @Volatile
    private var entry: Entry? = null

    fun usersFor(serverUrl: String): List<User>? =
        entry?.takeIf { it.serverUrl == serverUrl }?.users

    fun store(serverUrl: String, users: List<User>) {
        entry = Entry(serverUrl, users)
    }
}
