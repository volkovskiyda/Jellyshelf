package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val serverUrl: String,
    val apiKey: String,
    val userId: String,
    val userName: String,
    val libraryId: String,
    val libraryName: String,
    val indexUrl: String,
    val lastSyncAt: Long,
) {
    val hasCredentials: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val isConnected: Boolean get() = hasCredentials && userId.isNotBlank()
}

class SettingsRepository(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val LIBRARY_ID = stringPreferencesKey("library_id")
        val LIBRARY_NAME = stringPreferencesKey("library_name")
        val INDEX_URL = stringPreferencesKey("index_url")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    }

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            serverUrl = p[Keys.SERVER_URL].orEmpty(),
            apiKey = p[Keys.API_KEY].orEmpty(),
            userId = p[Keys.USER_ID].orEmpty(),
            userName = p[Keys.USER_NAME].orEmpty(),
            libraryId = p[Keys.LIBRARY_ID].orEmpty(),
            libraryName = p[Keys.LIBRARY_NAME].orEmpty(),
            indexUrl = p[Keys.INDEX_URL].orEmpty(),
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    suspend fun setConnection(serverUrl: String, apiKey: String) {
        ds.edit {
            it[Keys.SERVER_URL] = serverUrl.trim()
            it[Keys.API_KEY] = apiKey.trim()
        }
    }

    suspend fun setUser(userId: String, userName: String) {
        ds.edit {
            it[Keys.USER_ID] = userId
            it[Keys.USER_NAME] = userName
        }
    }

    /** Library/collection to scope sync to. Empty id == root == all collections. */
    suspend fun setLibrary(libraryId: String, libraryName: String) {
        ds.edit {
            it[Keys.LIBRARY_ID] = libraryId
            it[Keys.LIBRARY_NAME] = libraryName
        }
    }

    suspend fun setIndexUrl(url: String) {
        ds.edit { it[Keys.INDEX_URL] = url.trim() }
    }

    suspend fun setLastSyncAt(timestamp: Long) {
        ds.edit { it[Keys.LAST_SYNC_AT] = timestamp }
    }
}
