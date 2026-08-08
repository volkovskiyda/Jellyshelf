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
    /**
     * Shown as "Client" in Jellyfin's dashboard and in session lists — and the *only* thing a
     * stats tool has to classify this install by, since Jellyfin stores no platform or form-factor
     * field. They keyword-match this string; a name without "Android" in it leaves them echoing the
     * bare app name back as the platform. See `CLIENT_NAME` in `di/AppModule.kt`.
     */
    val clientName: String,
    /** Shown as "Device" — what the owner calls this device, see [deviceDisplayName]. */
    val deviceName: String,
    /** App version, shown alongside the client. */
    val version: String,
)

/**
 * What to send as `Device` — the name a person would recognise, not a codename.
 *
 * [userDeviceName] is the owner's own name for the device (`Settings.Global.DEVICE_NAME`), which
 * is what the official Jellyfin clients report and what a dashboard shows to identify one session
 * among several. Android pre-fills it with the bare model, so that case falls through to the
 * manufacturer-qualified form: "Pixel 7 Pro" alone says less than "Google Pixel 7 Pro".
 */
fun deviceDisplayName(userDeviceName: String?, manufacturer: String, model: String): String {
    val owned = userDeviceName?.trim().orEmpty()
    if (owned.isNotEmpty() && !owned.equals(model.trim(), ignoreCase = true)) return owned

    // Vendors are inconsistent about whether the model already names the brand: "Xiaomi 14" does,
    // "SM-S911B" does not.
    if (model.startsWith(manufacturer, ignoreCase = true)) return model
    return "${manufacturer.replaceFirstChar(Char::uppercaseChar)} $model"
}

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
