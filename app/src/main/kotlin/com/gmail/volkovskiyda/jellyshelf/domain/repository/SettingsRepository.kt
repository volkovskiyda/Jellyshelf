package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import kotlinx.coroutines.flow.Flow

/** Persistent app/connection settings. Backed by DataStore in the data layer. */
@Suppress("TooManyFunctions") // one accessor per persisted setting
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

    /** Advanced playback handoff: credential in the URL query rather than an intent header. */
    suspend fun setTokenInQuery(enabled: Boolean)

    /** App-wide mode the split play button acts with; its dropdown persists the pick here. */
    suspend fun setPlaybackMode(mode: PlaybackMode)

    /** Records that a sync finished at [timestamp] against library scope [libraryId]. */
    suspend fun setLastSync(timestamp: Long, libraryId: String)

    /** The Categories dimension (tab) the user last settled on, or null if never set. */
    val selectedCategoryType: Flow<String?>
    suspend fun setSelectedCategoryType(type: String)

    /**
     * The theme override and the direction the next tap of the switch moves in. Defaults to
     * [ThemeMode.AUTO] — follow the system — so an install that never touches it is unaffected.
     */
    val themeState: Flow<ThemeState>
    suspend fun setThemeState(state: ThemeState)

    /** The serialized navigation back stack, or null if none has been saved yet. */
    val backStackJson: Flow<String?>
    suspend fun setBackStackJson(json: String)

    /**
     * The library's duration filter, or null for no filter. Stored as [DurationBucket.id] — the
     * key already documented as stable and persistable — and restored leniently: a bucket that no
     * longer exists reads back as null (no filter) rather than failing a launch.
     *
     * Persisted for the same reason as [selectedCategoryType]: it is a deliberate mode with a
     * visible affordance, and the app already restores where you were and which dimension tab you
     * had open. The search query deliberately is *not* persisted — users expect it to reset.
     */
    val libraryDurationFilter: Flow<DurationBucket?>
    suspend fun setLibraryDurationFilter(bucket: DurationBucket?)

    /** Whether Categories search spans every dimension rather than the open tab. */
    val categoriesSearchAll: Flow<Boolean>
    suspend fun setCategoriesSearchAll(enabled: Boolean)

    /**
     * Whether the user has already been shown that the sync scope is still "all collections".
     *
     * Persisted rather than held in memory because the nudge is once *per sign-in*, and a process
     * death between signing in and the first sync must not re-arm it — that would make the nudge
     * an intermittent extra tap rather than a one-off. Cleared on a successful sign-in.
     */
    val syncScopeNudged: Flow<Boolean>
    suspend fun setSyncScopeNudged(nudged: Boolean)
}
