package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * How the server is delivering what the in-app player is playing, as Jellyfin names it in its
 * session reports and shows it on the dashboard.
 *
 * The app asks for [DirectPlay] every time and only ends up on [Transcode] when a decode failure
 * sends it to the HLS fallback, so this is not a setting — it is an observation about the stream
 * currently loaded. The names are the wire values; nothing maps them.
 */
enum class PlayMethod {
    /** The original file, streamed as-is. */
    DirectPlay,

    /** The server's HLS transcode, after the direct stream failed to decode. */
    Transcode,
}
