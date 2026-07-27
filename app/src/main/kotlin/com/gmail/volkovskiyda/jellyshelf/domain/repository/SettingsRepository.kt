package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import kotlinx.coroutines.flow.Flow

/** Persistent app/connection settings. Backed by DataStore in the data layer. */
interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun snapshot(): Settings

    suspend fun setConnection(serverUrl: String, apiKey: String)
    suspend fun setUser(userId: String, userName: String)

    /**
     * Records a successful password sign-in: the user-scoped token and who it belongs to. The
     * password itself is never passed here — it is discarded as soon as the token comes back.
     */
    suspend fun setSession(accessToken: String, userId: String, userName: String)

    /**
     * Drops the user token (and the user it identified), leaving the server URL and the advanced
     * API key alone. Used on sign-out and when the server rejects the token as expired.
     */
    suspend fun clearSession()

    /**
     * Stable per-install id sent as `DeviceId`. Generated and persisted on first use: Jellyfin
     * keys a session on it, so a fresh value each launch would litter the dashboard with devices.
     */
    suspend fun deviceId(): String

    /** Library/collection to scope sync to. Empty id == root == all collections. */
    suspend fun setLibrary(libraryId: String, libraryName: String)
    suspend fun setIndexUrl(url: String)

    /** Records that a sync finished at [timestamp] against library scope [libraryId]. */
    suspend fun setLastSync(timestamp: Long, libraryId: String)

    /** The Categories dimension (tab) the user last settled on, or null if never set. */
    val selectedCategoryType: Flow<String?>
    suspend fun setSelectedCategoryType(type: String)

    /** The serialized navigation back stack, or null if none has been saved yet. */
    val backStackJson: Flow<String?>
    suspend fun setBackStackJson(json: String)
}
