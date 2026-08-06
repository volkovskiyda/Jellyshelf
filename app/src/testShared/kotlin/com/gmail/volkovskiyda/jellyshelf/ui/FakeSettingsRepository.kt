package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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
@Suppress("TooManyFunctions") // mirrors the interface it fakes
class FakeSettingsRepository(
    initial: Settings = emptySettings,
    backStackJson: String? = null,
    selectedCategoryType: String? = null,
    themeState: ThemeState = ThemeState(),
    libraryDurationFilter: DurationBucket? = null,
    categoriesSearchAll: Boolean = false,
    syncScopeNudged: Boolean = false,
    updateSource: UpdateSource = UpdateSource.NONE,
    lastUpdateCheckAt: Long = 0L,
    lastUpdateDialogAt: Long = 0L,
) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    private val _backStackJson = MutableStateFlow(backStackJson)
    private val _selectedCategoryType = MutableStateFlow(selectedCategoryType)
    private val _themeState = MutableStateFlow(themeState)
    private val _libraryDurationFilter = MutableStateFlow(libraryDurationFilter)
    private val _categoriesSearchAll = MutableStateFlow(categoriesSearchAll)
    private val _syncScopeNudged = MutableStateFlow(syncScopeNudged)
    private val _updateSource = MutableStateFlow(updateSource)
    private val _lastUpdateCheckAt = MutableStateFlow(lastUpdateCheckAt)
    private val _lastUpdateDialogAt = MutableStateFlow(lastUpdateDialogAt)

    // Per source, so a test can snooze GitHub without touching App Distribution — the independence
    // the two key pairs exist for. Absent entries read as 0, matching the real store's "unset".
    private val _dismissedUpdate = MutableStateFlow(emptyMap<UpdateSource, Int>())
    private val _dismissedUpdateAt = MutableStateFlow(emptyMap<UpdateSource, Long>())

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

    override suspend fun setTokenInQuery(enabled: Boolean) {
        _settings.value = _settings.value.copy(tokenInQuery = enabled)
    }

    override suspend fun setPlaybackMode(mode: PlaybackMode) {
        _settings.value = _settings.value.copy(playbackMode = mode)
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        _settings.value = _settings.value.copy(playbackSpeed = speed)
    }

    override suspend fun setIndexUrl(url: String) {
        _settings.value = _settings.value.copy(indexUrl = url)
    }

    override suspend fun setLastSync(timestamp: Long, libraryId: String) {
        _settings.value = _settings.value.copy(lastSyncAt = timestamp, lastSyncLibraryId = libraryId)
    }

    override suspend fun setDemoMode(enabled: Boolean) {
        _settings.value = _settings.value.copy(demoMode = enabled)
    }

    override val selectedCategoryType: Flow<String?> = _selectedCategoryType
    override suspend fun setSelectedCategoryType(type: String) {
        _selectedCategoryType.value = type
    }

    override val themeState: Flow<ThemeState> = _themeState
    override suspend fun setThemeState(state: ThemeState) {
        _themeState.value = state
    }

    override val backStackJson: Flow<String?> = _backStackJson
    override suspend fun setBackStackJson(json: String) {
        _backStackJson.value = json
    }

    override val libraryDurationFilter: Flow<DurationBucket?> = _libraryDurationFilter
    override suspend fun setLibraryDurationFilter(bucket: DurationBucket?) {
        _libraryDurationFilter.value = bucket
    }

    override val categoriesSearchAll: Flow<Boolean> = _categoriesSearchAll
    override suspend fun setCategoriesSearchAll(enabled: Boolean) {
        _categoriesSearchAll.value = enabled
    }

    override val syncScopeNudged: Flow<Boolean> = _syncScopeNudged
    override suspend fun setSyncScopeNudged(nudged: Boolean) {
        _syncScopeNudged.value = nudged
    }

    override val updateSource: Flow<UpdateSource> = _updateSource
    override suspend fun setUpdateSource(source: UpdateSource) {
        _updateSource.value = source
    }

    override fun dismissedUpdate(source: UpdateSource): Flow<Int> =
        _dismissedUpdate.map { it[source] ?: 0 }

    override fun dismissedUpdateAt(source: UpdateSource): Flow<Long> =
        _dismissedUpdateAt.map { it[source] ?: 0L }

    /** Both halves together, like the real store — see [SettingsRepository.setDismissedUpdate]. */
    override suspend fun setDismissedUpdate(
        source: UpdateSource,
        versionCode: Int,
        timestamp: Long,
    ) {
        if (source == UpdateSource.NONE) return
        _dismissedUpdate.value += source to versionCode
        _dismissedUpdateAt.value += source to timestamp
    }

    override val lastUpdateCheckAt: Flow<Long> = _lastUpdateCheckAt
    override suspend fun setLastUpdateCheckAt(timestamp: Long) {
        _lastUpdateCheckAt.value = timestamp
    }

    override val lastUpdateDialogAt: Flow<Long> = _lastUpdateDialogAt
    override suspend fun setLastUpdateDialogAt(timestamp: Long) {
        _lastUpdateDialogAt.value = timestamp
    }

    /** What [setBackStackJson] last persisted. */
    val savedBackStackJson: String? get() = _backStackJson.value

    /** What [setLastUpdateCheckAt] last persisted, without collecting the flow. */
    val savedLastUpdateCheckAt: Long get() = _lastUpdateCheckAt.value

    /** What [setLastUpdateDialogAt] last persisted, without collecting the flow. */
    val savedLastUpdateDialogAt: Long get() = _lastUpdateDialogAt.value
}
