package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
        val SELECTED_CATEGORY_TYPE = stringPreferencesKey("selected_category_type")
        val BACK_STACK = stringPreferencesKey("back_stack")
    }

    val settings: Flow<Settings> = ds.data
        // A transient disk read failure must degrade to defaults, not propagate an IOException
        // into every collector (and out of the sync worker).
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
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

    /**
     * The Categories dimension (tab) the user last settled on, or null if never set. A UI
     * preference kept out of [Settings] since it has nothing to do with the server connection.
     * Null on a disk read failure degrades to "no restore", matching [settings].
     */
    val selectedCategoryType: Flow<String?> = ds.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.SELECTED_CATEGORY_TYPE] }

    suspend fun setSelectedCategoryType(type: String) {
        ds.edit { it[Keys.SELECTED_CATEGORY_TYPE] = type }
    }

    /**
     * The serialized navigation back stack, or null if none has been saved yet. Persisted on every
     * navigation so the app reopens on the exact screen the user left, restored by MainViewModel.
     * Serialization (an AppNavKey list) lives in the ViewModel; the repo stays a plain string store.
     */
    val backStackJson: Flow<String?> = ds.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.BACK_STACK] }

    suspend fun setBackStackJson(json: String) {
        ds.edit { it[Keys.BACK_STACK] = json }
    }
}
