package com.gmail.volkovskiyda.jellyshelf.util

import java.net.URLEncoder

// Thumbnail URLs are persisted without credentials; the api key is appended only at display
// time. These helpers keep that contract in one place.

/** True for URLs shaped like a Jellyfin image endpoint (vs. an index/YouTube CDN thumbnail). */
fun isJellyfinImageUrl(url: String): Boolean = "/Items/" in url && "/Images/" in url

/**
 * Turns whatever the user typed into the server-URL field into a usable absolute URL: trimmed,
 * and prefixed with `https://` when no scheme was given — people type `jf.example.app`, and
 * without a scheme Ktor treats the value as a relative path and quietly resolves requests
 * against `http://localhost/`.
 *
 * `https` deliberately: it is the only scheme release builds allow, and the debug-LAN user who
 * genuinely wants plain HTTP can still type `http://` out. An explicit scheme — any string
 * containing `://` — always passes through untouched.
 */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank() || "://" in trimmed) return trimmed
    return "https://$trimmed"
}

/**
 * The query parameter Jellyfin reads a credential from, for the two URLs the app can't attach a
 * header to (an image handed to Coil, a stream handed to another app).
 *
 * Spelled with capitals because Jellyfin 12 matches it exactly: the lowercase `api_key` every
 * earlier version accepted is now ignored, and an ignored credential is an unauthenticated
 * request. [CREDENTIAL_PARAMS] still knows both spellings, since stripping is the opposite job.
 */
const val CREDENTIAL_PARAM = "ApiKey"

/**
 * Appends [apiKey] to a Jellyfin-hosted image [url] at display time; other URLs pass through.
 * The key is attached only to URLs on the configured [serverUrl]: index thumbnails are remote
 * input, so a URL merely *shaped* like a Jellyfin image path must never receive the server's
 * credential — it could point anywhere.
 */
fun authorizedImageUrl(url: String?, serverUrl: String?, apiKey: String?): String? {
    if (url.isNullOrBlank() || apiKey.isNullOrBlank()) return url
    if (!isJellyfinImageUrl(url) || hasCredential(url)) return url
    val base = serverUrl?.trim()?.removeSuffix("/")
    if (base.isNullOrBlank() || !url.startsWith("$base/")) return url
    val separator = if ('?' in url) '&' else '?'
    return "$url$separator$CREDENTIAL_PARAM=${URLEncoder.encode(apiKey, "UTF-8")}"
}

/** True when [url] already carries a credential under any of the spellings Jellyfin has used. */
private fun hasCredential(url: String): Boolean = url
    .substringAfter('?', "")
    .split('&')
    .any { it.substringBefore('=').lowercase() in CREDENTIAL_PARAMS }

/**
 * Query parameter names that carry a Jellyfin credential, lowercased. `apikey` is what this app
 * appends today ([CREDENTIAL_PARAM]); the others are spellings earlier servers accepted, kept
 * because a stored thumbnail URL or one that arrived from elsewhere can still carry them.
 *
 * The user access token and the admin API key travel under the *same* parameter names — Jellyfin
 * makes no distinction — so stripping these covers both.
 */
private val CREDENTIAL_PARAMS = setOf("api_key", "apikey", "x-emby-token")

/**
 * Drops any embedded credential query parameter from [url] — used both to scrub legacy stored
 * thumbnail URLs and to keep secrets out of the logs.
 *
 * Matching is case-insensitive on the parameter name: what must never survive is the *value*, and
 * a URL from another client can spell the key differently than this app does.
 */
fun stripCredentials(url: String?): String? {
    if (url.isNullOrBlank() || '?' !in url) return url
    val base = url.substringBefore('?')
    val params = url.substringAfter('?', "")
        .split('&')
        .filter { param ->
            param.isNotBlank() && param.substringBefore('=').lowercase() !in CREDENTIAL_PARAMS
        }
    return if (params.isEmpty()) base else params.joinToString("&", prefix = "$base?")
}
