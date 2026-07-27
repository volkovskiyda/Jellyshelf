package com.gmail.volkovskiyda.jellyshelf.domain.model

/** App/connection settings snapshot. Backed by DataStore in the data layer. */
data class Settings(
    val serverUrl: String,
    val apiKey: String,
    val userId: String,
    val userName: String,
    val libraryId: String,
    val libraryName: String,
    val indexUrl: String,
    val lastSyncAt: Long,
    /**
     * The [libraryId] the last sync actually ran against. When it differs from [libraryId] the
     * user has re-scoped the library, so videos outside the new scope are gone by explicit intent
     * and sync deletes them at once instead of waiting out the missed-sync grace period.
     */
    val lastSyncLibraryId: String,
) {
    val hasCredentials: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val isConnected: Boolean get() = hasCredentials && userId.isNotBlank()
}
