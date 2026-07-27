package com.gmail.volkovskiyda.jellyshelf.domain

/**
 * How this install identifies itself to Jellyfin. Injected rather than read from `Build`/
 * `BuildConfig` at the call site, so tests and previews get stable values.
 *
 * [deviceId] is not here: it is persisted (see `SettingsRepository.deviceId`) because Jellyfin
 * keys a session on it, and a value that changed per launch would litter the dashboard with a new
 * device every time the app started.
 */
data class DeviceInfo(
    /** Shown as "Client" in Jellyfin's dashboard and in session lists. */
    val clientName: String,
    /** Shown as "Device" — the phone model. */
    val deviceName: String,
    /** App version, shown alongside the client. */
    val version: String,
)

/**
 * The `Authorization: MediaBrowser …` header Jellyfin requires on `AuthenticateByName` (there is
 * no token yet to put in `X-Emby-Token`).
 *
 * It is also what fixes the reporting symptom this item was raised for: authenticating with an API
 * key attaches no client identity at all, so sessions showed up as user "Unknown", player
 * "Jellyfin Server", and the *server's* SystemId as the device. These four fields are what
 * Jellyfin displays instead.
 *
 * Values are sanitized because this is an HTTP header: quotes would terminate a field early, and
 * `Build.MODEL` is free-form vendor text that is not guaranteed to be ASCII.
 */
fun mediaBrowserAuthHeader(info: DeviceInfo, deviceId: String): String = listOf(
    "Client" to info.clientName,
    "Device" to info.deviceName,
    "DeviceId" to deviceId,
    "Version" to info.version,
).joinToString(", ", prefix = "MediaBrowser ") { (name, value) ->
    """$name="${headerSafe(value)}""""
}

// Printable US-ASCII, the only range an HTTP header field value may contain unescaped.
private const val ASCII_SPACE = 0x20
private const val ASCII_TILDE = 0x7E

/** Drops anything that can't sit inside a quoted header field; never returns blank. */
private fun headerSafe(value: String): String =
    value.filter { it.code in ASCII_SPACE..ASCII_TILDE && it != '"' && it != '\\' }
        .trim()
        .ifBlank { "unknown" }
