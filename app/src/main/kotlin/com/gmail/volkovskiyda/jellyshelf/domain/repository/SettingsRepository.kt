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
     * Drops everything that describes a server: the token and its user, the server URL, the
     * advanced API key, the metadata index URL, the folder scope and the playback-handoff switch.
     * What survives is the device's own state — the install's [deviceId], the theme, the playback
     * preferences, the update-check settings — none of which came from a server.
     *
     * Sign-out's half of the wipe; the library rows are [LibraryRepository.clearLocalData]'s, and
     * [com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsViewModel.signOut] is the one caller
     * that does both. A superset of [clearSession], which stays for the *involuntary* case — an
     * expired token, where the connection the user configured is still the one they want.
     */
    suspend fun clearConnection()

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
     * The youtubeId the player was last on, or null when there is nothing to resume.
     *
     * What the system's media-resumption surfaces — the output switcher, a Bluetooth play button —
     * restart after the process is gone. It has to be a *stored* value rather than the back stack,
     * because player entries are deliberately never persisted there, and it is a preference rather
     * than a column because it is app state about the player, not a fact about a video: the schema
     * window closed at v1.0 and a column would need a real migration to say something Room has no
     * business knowing.
     *
     * Written whenever the queue moves to a video and cleared when the queue empties, which is the
     * explicit stop — "done watching" should not come back from a quick-settings tile. A process
     * killed with a video still loaded keeps the value, which is the whole point.
     */
    val lastPlayedVideoId: Flow<String?>
    suspend fun setLastPlayedVideoId(youtubeId: String?)

    /**
     * The versionCode already announced by a notification, `0` when none has been.
     *
     * The background check runs daily and an unacted-on offer survives every one of them, so
     * without this the same release would be announced every morning. Stamped when a notification
     * is actually posted, never when one is merely suppressed.
     *
     * Not a dismissal: swiping the notification away records nothing. The 7-day snooze belongs to
     * the dialog's explicit dismiss, which is a different act by a user who read the offer.
     */
    val lastNotifiedUpdate: Flow<Int>
    suspend fun setLastNotifiedUpdate(versionCode: Int)

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

    /**
     * Epoch millis the notification-permission prompt was last answered, `0` when never — the same
     * "0 == never == the window has elapsed" convention as the three update timestamps, so a fresh
     * install is asked as soon as it has a library rather than being muted for a week.
     */
    val notificationPromptAt: Flow<Long>

    /**
     * Whether the *system* permission dialog has ever been launched — i.e. whether the user got as
     * far as Android's own dialog rather than answering ours with "Not now".
     *
     * Stored because `shouldShowRequestPermissionRationale` reads `false` in two opposite
     * situations: before the first ask, and after the second denial has locked the permission for
     * good. This flag is what tells them apart, and so what stops the prompt returning every week
     * with an "Allow" button that the system would silently ignore.
     */
    val notificationSystemAsked: Flow<Boolean>

    /**
     * Records an answer to the prompt. Both halves in one write, like [setDismissedUpdate]: a
     * timestamp without its flag reads back as "asked, at the epoch", i.e. a snooze that expired
     * before it started.
     *
     * [systemAsked] only ever turns on — passing `false` after a system ask leaves the stored
     * `true` alone, since "we have shown Android's dialog before" cannot become untrue.
     */
    suspend fun setNotificationPrompt(timestamp: Long, systemAsked: Boolean)

    /**
     * Epoch millis the local-network-permission prompt was last answered, `0` when never — the
     * same "0 == never == the window has elapsed" convention as [notificationPromptAt], so a fresh
     * install is asked on its first visit to settings rather than being muted for a week.
     *
     * A separate pair of keys from the notification prompt's, not a shared one: the two permissions
     * are asked for on different screens for different reasons, and sharing a snooze would let one
     * silence the other.
     */
    val localNetworkPromptAt: Flow<Long>

    /**
     * Whether Android's own `ACCESS_LOCAL_NETWORK` dialog has ever been launched, for the same
     * reason as [notificationSystemAsked]: `shouldShowRequestPermissionRationale` reads `false`
     * both before the first ask and after the second denial has locked the permission for good,
     * and this flag is what tells those apart.
     */
    val localNetworkSystemAsked: Flow<Boolean>

    /**
     * Records an answer to the local-network prompt. Both halves in one write, and [systemAsked]
     * only ever turns on, exactly as in [setNotificationPrompt].
     *
     * A `0` timestamp is how a connection failure re-arms the prompt: it restores the "never
     * answered" state the store starts in, which cancels a decline without claiming Android has
     * not been asked.
     */
    suspend fun setLocalNetworkPrompt(timestamp: Long, systemAsked: Boolean)
}
