package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The device id [FakeSettingsRepository] always reports. */
const val FAKE_DEVICE_ID = "test-device-id"

/** Blank settings: no credentials, never synced — what a fresh install reads. */
val emptySettings = Settings(
    serverUrl = "",
    apiKey = "",
    accessToken = "",
    userId = "",
    userName = "",
    libraryId = "",
    libraryName = "",
    indexUrl = "",
    lastSyncAt = 0L,
    lastSyncLibraryId = "",
)

/** In-memory [SettingsRepository]: no DataStore, no disk, every write readable straight back. */
class FakeSettingsRepository(
    initial: Settings = emptySettings,
    backStackJson: String? = null,
    selectedCategoryType: String? = null,
) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    private val _backStackJson = MutableStateFlow(backStackJson)
    private val _selectedCategoryType = MutableStateFlow(selectedCategoryType)

    override val settings: Flow<Settings> = _settings
    override suspend fun snapshot(): Settings = _settings.value

    override suspend fun setConnection(serverUrl: String, apiKey: String) {
        _settings.value = _settings.value.copy(serverUrl = serverUrl, apiKey = apiKey)
    }

    override suspend fun setUser(userId: String, userName: String) {
        _settings.value = _settings.value.copy(userId = userId, userName = userName)
    }

    override suspend fun setSession(accessToken: String, userId: String, userName: String) {
        _settings.value = _settings.value.copy(
            accessToken = accessToken,
            userId = userId,
            userName = userName,
        )
    }

    override suspend fun clearSession() {
        _settings.value = _settings.value.copy(accessToken = "", userId = "", userName = "")
    }

    /** Fixed rather than random: a test asserting on the auth header needs a predictable id. */
    override suspend fun deviceId(): String = FAKE_DEVICE_ID

    override suspend fun setLibrary(libraryId: String, libraryName: String) {
        _settings.value = _settings.value.copy(libraryId = libraryId, libraryName = libraryName)
    }

    override suspend fun setIndexUrl(url: String) {
        _settings.value = _settings.value.copy(indexUrl = url)
    }

    override suspend fun setLastSync(timestamp: Long, libraryId: String) {
        _settings.value = _settings.value.copy(lastSyncAt = timestamp, lastSyncLibraryId = libraryId)
    }

    override val selectedCategoryType: Flow<String?> = _selectedCategoryType
    override suspend fun setSelectedCategoryType(type: String) {
        _selectedCategoryType.value = type
    }

    override val backStackJson: Flow<String?> = _backStackJson
    override suspend fun setBackStackJson(json: String) {
        _backStackJson.value = json
    }

    /** What [setBackStackJson] last persisted. */
    val savedBackStackJson: String? get() = _backStackJson.value
}
