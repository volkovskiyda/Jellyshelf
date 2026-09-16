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
 * The `Authorization: MediaBrowser …` header — the *only* way Jellyfin 12 accepts a credential.
 *
 * Up to 12.0 a token could also travel as `X-Emby-Token`, `X-MediaBrowser-Token` or an `api_key`
 * query parameter. All three now answer 401 (measured against a 12.0.0 server on 2026-09-16); the
 * server's own OpenAPI declares a single security scheme, an api-key in the `Authorization`
 * header. So this header carries the credential for every authenticated call, not just the login.
 *
 * [token] is absent for exactly one call — `AuthenticateByName`, which is what mints it. Every
 * other request passes the user access token or the advanced admin API key, and Jellyfin makes no
 * distinction between the two here.
 *
 * Sending the identity fields alongside the token is also what fixes the reporting symptom this
 * was first raised for: an admin API key carries no client identity of its own, so sessions showed
 * up as user "Unknown", player "Jellyfin Server", and the *server's* SystemId as the device. A
 * user token is bound to the device it was issued to, but the API key is not — these four fields
 * are what Jellyfin displays instead.
 *
 * Values are sanitized because this is an HTTP header: quotes would terminate a field early, and
 * `Build.MODEL` is free-form vendor text that is not guaranteed to be ASCII.
 */
fun mediaBrowserAuthHeader(info: DeviceInfo, deviceId: String, token: String? = null): String =
    buildList {
        add("Client" to info.clientName)
        add("Device" to info.deviceName)
        add("DeviceId" to deviceId)
        add("Version" to info.version)
        // Last, matching the order the official clients send — and omitted entirely rather than
        // sent blank, so the login request carries identity without an empty credential field.
        if (!token.isNullOrBlank()) add("Token" to token)
    }.joinToString(", ", prefix = "$MEDIA_BROWSER_SCHEME ") { (name, value) ->
        """$name="${headerSafe(value)}""""
    }

/**
 * The same header with nothing but the credential — what the media stack sends.
 *
 * The identity fields are dropped there on purpose: a stream request is not what registers a
 * session (the `Sessions/Playing` trio is, and that goes through the API client above), and the
 * ExoPlayer datasource factory and the external-player intent are both built on threads that
 * cannot read the persisted device id. A token already identifies its own device to the server.
 */
fun mediaBrowserTokenHeader(token: String): String =
    """$MEDIA_BROWSER_SCHEME Token="${headerSafe(token)}""""

/** Jellyfin's auth scheme name. Matched case-insensitively by the server, sent as it documents it. */
private const val MEDIA_BROWSER_SCHEME = "MediaBrowser"

// Printable US-ASCII, the only range an HTTP header field value may contain unescaped.
private const val ASCII_SPACE = 0x20
private const val ASCII_TILDE = 0x7E

/** Drops anything that can't sit inside a quoted header field; never returns blank. */
private fun headerSafe(value: String): String =
    value.filter { it.code in ASCII_SPACE..ASCII_TILDE && it != '"' && it != '\\' }
        .trim()
        .ifBlank { "unknown" }
