package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackSpeed
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

internal val Context.dataStore by preferencesDataStore(name = "settings")

@Suppress("TooManyFunctions") // one accessor per persisted setting
class DefaultSettingsRepository(context: Context) : SettingsRepository {
    private val ds = context.applicationContext.dataStore

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val LIBRARY_ID = stringPreferencesKey("library_id")
        val LIBRARY_NAME = stringPreferencesKey("library_name")
        val INDEX_URL = stringPreferencesKey("index_url")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_LIBRARY_ID = stringPreferencesKey("last_sync_library_id")
        val SELECTED_CATEGORY_TYPE = stringPreferencesKey("selected_category_type")
        val BACK_STACK = stringPreferencesKey("back_stack")
        val TOKEN_IN_QUERY = booleanPreferencesKey("token_in_query")
        val PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_TOWARD_DARK = booleanPreferencesKey("theme_toward_dark")
        val LIBRARY_DURATION_FILTER = stringPreferencesKey("library_duration_filter")
        val CATEGORIES_SEARCH_ALL = booleanPreferencesKey("categories_search_all")
        val SYNC_SCOPE_NUDGED = booleanPreferencesKey("sync_scope_nudged")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
    }

    /**
     * Every read starts here rather than at [ds] directly: a transient disk read failure must
     * degrade to defaults, not propagate an IOException into every collector (and out of the sync
     * worker). One flow, so no accessor can be added later that forgets the rule — the whole file
     * has the same "unreadable preferences read as unset" contract, and each accessor's KDoc says
     * what unset means for it.
     *
     * Non-IOException failures still throw: those are bugs (a bad key type, a serializer fault),
     * and swallowing them would hide a setting that silently never persists.
     */
    private val prefs: Flow<Preferences> = ds.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val settings: Flow<Settings> = prefs
        .map { p ->
            Settings(
                serverUrl = p[Keys.SERVER_URL].orEmpty(),
                apiKey = p[Keys.API_KEY].orEmpty(),
                accessToken = p[Keys.ACCESS_TOKEN].orEmpty(),
                userId = p[Keys.USER_ID].orEmpty(),
                userName = p[Keys.USER_NAME].orEmpty(),
                libraryId = p[Keys.LIBRARY_ID].orEmpty(),
                libraryName = p[Keys.LIBRARY_NAME].orEmpty(),
                indexUrl = p[Keys.INDEX_URL].orEmpty(),
                lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
                lastSyncLibraryId = p[Keys.LAST_SYNC_LIBRARY_ID].orEmpty(),
                tokenInQuery = p[Keys.TOKEN_IN_QUERY] ?: false,
                // By-name lookup so an unknown/absent stored value degrades to the default.
                playbackMode = PlaybackMode.entries.firstOrNull { it.name == p[Keys.PLAYBACK_MODE] }
                    ?: PlaybackMode.PLAY,
                // Validated against the menu's own list, so a speed a later build drops (or a
                // corrupt value) starts at 1× rather than at one no menu item can tick.
                playbackSpeed = PlaybackSpeed.fromStorage(p[Keys.PLAYBACK_SPEED]),
                // Unset (and unreadable) reads as "not a demo" — the safe direction: a real
                // library presented as a demo would hide server actions that genuinely work.
                demoMode = p[Keys.DEMO_MODE] ?: false,
            )
        }

    override suspend fun snapshot(): Settings = settings.first()

    override suspend fun setConnection(serverUrl: String, apiKey: String) {
        ds.edit {
            it[Keys.SERVER_URL] = serverUrl.trim()
            it[Keys.API_KEY] = apiKey.trim()
        }
    }

    override suspend fun setUser(userId: String, userName: String) {
        ds.edit {
            it[Keys.USER_ID] = userId
            it[Keys.USER_NAME] = userName
        }
    }

    override suspend fun setSession(accessToken: String, userId: String, userName: String) {
        ds.edit {
            it[Keys.ACCESS_TOKEN] = accessToken
            it[Keys.USER_ID] = userId
            it[Keys.USER_NAME] = userName
        }
    }

    override suspend fun clearSession() {
        ds.edit {
            it.remove(Keys.ACCESS_TOKEN)
            // The user came from the token, so it goes with it. The server URL and the advanced
            // API key stay: re-signing in shouldn't mean retyping the connection.
            it.remove(Keys.USER_ID)
            it.remove(Keys.USER_NAME)
        }
    }

    override suspend fun deviceId(): String {
        snapshotDeviceId()?.let { return it }
        // Generated under edit() so two concurrent first-callers agree: DataStore serializes
        // transforms, and the second one sees the first one's value instead of overwriting it.
        return ds.edit { it[Keys.DEVICE_ID] = it[Keys.DEVICE_ID] ?: UUID.randomUUID().toString() }
            .let { it[Keys.DEVICE_ID].orEmpty() }
    }

    private suspend fun snapshotDeviceId(): String? = prefs
        .first()[Keys.DEVICE_ID]
        ?.takeIf { it.isNotBlank() }

    /** Library/collection to scope sync to. Empty id == root == all collections. */
    override suspend fun setLibrary(libraryId: String, libraryName: String) {
        ds.edit {
            it[Keys.LIBRARY_ID] = libraryId
            it[Keys.LIBRARY_NAME] = libraryName
        }
    }

    override suspend fun setTokenInQuery(enabled: Boolean) {
        ds.edit { it[Keys.TOKEN_IN_QUERY] = enabled }
    }

    override suspend fun setPlaybackMode(mode: PlaybackMode) {
        ds.edit { it[Keys.PLAYBACK_MODE] = mode.name }
    }

    /**
     * Stored as a float, not as a name like [PlaybackMode]: this is a number the player consumes
     * directly, and every option is a binary fraction, so the round trip is exact. Validation is on
     * the read side only, matching [setLibraryDurationFilter].
     */
    override suspend fun setPlaybackSpeed(speed: Float) {
        ds.edit { it[Keys.PLAYBACK_SPEED] = speed }
    }

    override suspend fun setIndexUrl(url: String) {
        ds.edit { it[Keys.INDEX_URL] = url.trim() }
    }

    override suspend fun setLastSync(timestamp: Long, libraryId: String) {
        ds.edit {
            it[Keys.LAST_SYNC_AT] = timestamp
            it[Keys.LAST_SYNC_LIBRARY_ID] = libraryId
        }
    }

    override suspend fun setDemoMode(enabled: Boolean) {
        ds.edit { it[Keys.DEMO_MODE] = enabled }
    }

    /**
     * The Categories dimension (tab) the user last settled on, or null if never set. A UI
     * preference kept out of [Settings] since it has nothing to do with the server connection.
     * Null on a disk read failure degrades to "no restore", matching [settings].
     */
    override val selectedCategoryType: Flow<String?> = prefs
        .map { it[Keys.SELECTED_CATEGORY_TYPE] }

    override suspend fun setSelectedCategoryType(type: String) {
        ds.edit { it[Keys.SELECTED_CATEGORY_TYPE] = type }
    }

    /**
     * The theme override, defaulting to auto — which is also what an unreadable preferences file
     * degrades to, so a disk failure leaves the app following the system rather than blank.
     */
    override val themeState: Flow<ThemeState> = prefs
        .map {
            ThemeState(
                mode = ThemeMode.fromStorage(it[Keys.THEME_MODE]),
                towardDark = it[Keys.THEME_TOWARD_DARK] ?: true,
            )
        }

    override suspend fun setThemeState(state: ThemeState) {
        ds.edit {
            it[Keys.THEME_MODE] = state.mode.storageValue
            it[Keys.THEME_TOWARD_DARK] = state.towardDark
        }
    }

    /**
     * The serialized navigation back stack, or null if none has been saved yet. Persisted on every
     * navigation so the app reopens on the exact screen the user left, restored by MainViewModel.
     * Serialization (an AppNavKey list) lives in the ViewModel; the repo stays a plain string store.
     */
    override val backStackJson: Flow<String?> = prefs
        .map { it[Keys.BACK_STACK] }

    override suspend fun setBackStackJson(json: String) {
        ds.edit { it[Keys.BACK_STACK] = json }
    }

    /**
     * Matched by [DurationBucket.id], so an id this version no longer knows — a bucket removed or
     * renumbered later — reads back as "no filter" instead of throwing on the launch that restores
     * it. Same degrade-to-default contract as [settings] and [selectedCategoryType].
     */
    override val libraryDurationFilter: Flow<DurationBucket?> = prefs
        .map { p ->
            p[Keys.LIBRARY_DURATION_FILTER]
                ?.let { stored -> DurationBucket.entries.firstOrNull { it.id == stored } }
        }

    /** Null clears the key rather than storing a sentinel for "no filter". */
    override suspend fun setLibraryDurationFilter(bucket: DurationBucket?) {
        ds.edit {
            if (bucket == null) {
                it.remove(Keys.LIBRARY_DURATION_FILTER)
            } else {
                it[Keys.LIBRARY_DURATION_FILTER] = bucket.id
            }
        }
    }

    override val categoriesSearchAll: Flow<Boolean> = prefs
        .map { it[Keys.CATEGORIES_SEARCH_ALL] ?: false }

    override suspend fun setCategoriesSearchAll(enabled: Boolean) {
        ds.edit { it[Keys.CATEGORIES_SEARCH_ALL] = enabled }
    }

    /** Unreadable preferences degrade to "not yet nudged" — one spare nudge, never a lost sync. */
    override val syncScopeNudged: Flow<Boolean> = prefs
        .map { it[Keys.SYNC_SCOPE_NUDGED] ?: false }

    override suspend fun setSyncScopeNudged(nudged: Boolean) {
        ds.edit { it[Keys.SYNC_SCOPE_NUDGED] = nudged }
    }
}
