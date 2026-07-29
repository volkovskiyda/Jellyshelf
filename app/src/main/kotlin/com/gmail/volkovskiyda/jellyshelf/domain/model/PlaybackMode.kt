package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * How the detail screen's split play button starts playback. Saved app-wide by picking a mode
 * from the button's dropdown — the dropdown is the setting UI; there is no Settings-screen row.
 * Persisted by name; an unknown stored value degrades to [PLAY].
 */
enum class PlaybackMode {
    /** In-app direct playback on the built-in player screen. */
    PLAY,

    /** Hand playback to another app via the external-player chooser intent. */
    EXTERNAL,

    /** Open the video's details page in the Jellyfin web UI. */
    WEB,
}
