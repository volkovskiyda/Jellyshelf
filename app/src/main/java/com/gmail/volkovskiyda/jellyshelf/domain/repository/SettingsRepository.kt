package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import kotlinx.coroutines.flow.Flow

/** Persistent app/connection settings. Backed by DataStore in the data layer. */
interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun snapshot(): Settings

    suspend fun setConnection(serverUrl: String, apiKey: String)
    suspend fun setUser(userId: String, userName: String)

    /** Library/collection to scope sync to. Empty id == root == all collections. */
    suspend fun setLibrary(libraryId: String, libraryName: String)
    suspend fun setIndexUrl(url: String)
    suspend fun setLastSyncAt(timestamp: Long)

    /** The Categories dimension (tab) the user last settled on, or null if never set. */
    val selectedCategoryType: Flow<String?>
    suspend fun setSelectedCategoryType(type: String)

    /** The serialized navigation back stack, or null if none has been saved yet. */
    val backStackJson: Flow<String?>
    suspend fun setBackStackJson(json: String)
}
