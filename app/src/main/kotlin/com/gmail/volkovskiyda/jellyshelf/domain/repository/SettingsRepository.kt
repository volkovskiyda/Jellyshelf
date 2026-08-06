package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaybackMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
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

    /**
     * App-wide speed the in-app player starts at; the player's speed menu persists the pick here
     * and it reads back off [Settings.playbackSpeed].
     */
    suspend fun setPlaybackSpeed(speed: Float)

    /** Records that a sync finished at [timestamp] against library scope [libraryId]. */
    suspend fun setLastSync(timestamp: Long, libraryId: String)

    /**
     * Records whether the library currently holds seeded demo data. Written by the demo seeder and
     * cleared with the data itself by [LibraryRepository.clearLocalData], so the flag can never
     * describe rows that are no longer there.
     */
    suspend fun setDemoMode(enabled: Boolean)

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

    /**
     * Which channel to check for a newer build of the app, defaulting to [UpdateSource.NONE] — so
     * an install that never opts in, and an unreadable preferences file, both make no network call.
     */
    val updateSource: Flow<UpdateSource>
    suspend fun setUpdateSource(source: UpdateSource)

    /**
     * Highest version code the user has dismissed for [source]; `0` when none — and `0` is also
     * what [UpdateSource.NONE] always reads, since a channel that never checks has nothing to
     * dismiss. Kept per source so switching channels doesn't inherit the other one's silence.
     */
    fun dismissedUpdate(source: UpdateSource): Flow<Int>

    /**
     * When that dismissal happened, epoch millis; `0` when never. Paired with [dismissedUpdate] —
     * a dismissal is a snooze that expires, so the code alone doesn't say whether it still applies.
     */
    fun dismissedUpdateAt(source: UpdateSource): Flow<Long>

    /**
     * Records both halves at once — they must never disagree. Two setters would invite a caller
     * that writes the code and not the time, which reads back as "dismissed at the epoch": already
     * expired, so the dialog returns on the very next launch.
     */
    suspend fun setDismissedUpdate(source: UpdateSource, versionCode: Int, timestamp: Long)

    /**
     * Epoch millis of the last completed check, `0` when never. Shared across sources: it throttles
     * how often the app *asks*, which is about network politeness rather than about any one
     * channel. `0` means "the window has elapsed", so a fresh install checks on first launch.
     */
    val lastUpdateCheckAt: Flow<Long>
    suspend fun setLastUpdateCheckAt(timestamp: Long)

    /**
     * Epoch millis the update dialog was last actually shown, `0` when never. Shared across sources
     * too: the once-a-day floor is about not nagging, and the user does not care which channel the
     * nag came from. Same `0` == "elapsed" convention as [lastUpdateCheckAt] — the opposite reading
     * would mute a fresh install for a day.
     */
    val lastUpdateDialogAt: Flow<Long>
    suspend fun setLastUpdateDialogAt(timestamp: Long)
}
