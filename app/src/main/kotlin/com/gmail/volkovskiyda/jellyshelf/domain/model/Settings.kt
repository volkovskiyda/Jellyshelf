package com.gmail.volkovskiyda.jellyshelf.domain.model

/** App/connection settings snapshot. Backed by DataStore in the data layer. */
data class Settings(
    val serverUrl: String,
    /**
     * Server-wide (admin-scoped) API key — the **advanced fallback**, used only when no user is
     * signed in. Kept for setups that can't use a password login; [accessToken] is the default.
     */
    val apiKey: String,
    /**
     * User-scoped access token from `AuthenticateByName`, or blank when signed out. The password
     * that produced it is never stored.
     */
    val accessToken: String,
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
    /**
     * Advanced: put the credential in the stream URL's query instead of an intent-extra header
     * when handing playback to an external player. Off by default — see
     * `Playback.externalPlayerIntent` for why the header is preferred, and why a chooser can't
     * pick per player.
     */
    val tokenInQuery: Boolean = false,
    /**
     * How the split play button acts: in-app player, external chooser, or web deep link. Picking
     * a mode from the button's dropdown saves it here app-wide.
     */
    val playbackMode: PlaybackMode = PlaybackMode.PLAY,
    /**
     * The speed the in-app player starts at, picked from the player's speed menu and applied to the
     * ExoPlayer when the playback service builds it. Always one of [PlaybackSpeed.options] — a
     * stored value this build no longer offers reads back as [PlaybackSpeed.DEFAULT].
     *
     * Press-and-hold's temporary 3× never reaches here: it is a gesture, not a choice.
     */
    val playbackSpeed: Float = PlaybackSpeed.DEFAULT,
    /**
     * Whether the library holds seeded demo data rather than a real server's. Set by the demo
     * seeder and cleared by Sign out's wipe — the flag never outlives the rows it describes.
     *
     * Read by the UI (which hides server-dependent affordances) and by playback (which plays a
     * bundled clip). Demo installs have no credentials, so [isConnected] is false throughout and
     * every network path already declines on its own; this flag is about *presentation*, not about
     * suppressing requests.
     */
    val demoMode: Boolean = false,
) {
    /**
     * The single value every authenticated request sends as `X-Emby-Token`: the user token when
     * signed in, otherwise the advanced API key. Jellyfin accepts either in that header, which is
     * what lets one credential flow through the whole data layer regardless of how it was obtained.
     *
     * Resolution lives here, in one place, so no call site has to decide which one it holds.
     */
    val credential: String get() = accessToken.ifBlank { apiKey }

    /** True when the credential is a user token — the default path — rather than the API key. */
    val isSignedIn: Boolean get() = accessToken.isNotBlank()

    val hasCredentials: Boolean get() = serverUrl.isNotBlank() && credential.isNotBlank()
    val isConnected: Boolean get() = hasCredentials && userId.isNotBlank()
}
