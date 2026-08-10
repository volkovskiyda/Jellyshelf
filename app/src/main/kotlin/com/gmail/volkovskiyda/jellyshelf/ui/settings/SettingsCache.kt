package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-lifetime Settings cache. Tab switches clear the Settings ViewModel, and without this
 * every visit to the tab would re-query the server for its users. Entries are keyed by the server
 * URL they came from, so a changed URL never serves another server's users.
 */
class SettingsCache {
    private data class Entry(val serverUrl: String, val users: List<User>)

    private val entry = MutableStateFlow<Entry?>(null)

    fun usersFor(serverUrl: String): List<User>? =
        entry.value?.takeIf { it.serverUrl == serverUrl }?.users

    fun store(serverUrl: String, users: List<User>) {
        entry.value = Entry(serverUrl, users)
    }

    /**
     * Forgets the cached users. Only a sign-out needs this: the entry is keyed by server URL, so a
     * *changed* URL already misses — but a sign-out clears the URL to blank and the next sign-in
     * may well retype the same one, which would otherwise hit a cache from before the wipe.
     */
    fun clear() {
        entry.value = null
    }
}
