package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto

/**
 * Process-lifetime Settings cache, owned by the app container. Tab switches clear the Settings
 * ViewModel, and without this every visit to the tab would re-query the server for its users.
 */
class SettingsCache {
    @Volatile
    var users: List<UserDto>? = null
}
