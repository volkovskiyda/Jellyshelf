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
) {
    val hasCredentials: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val isConnected: Boolean get() = hasCredentials && userId.isNotBlank()
}
