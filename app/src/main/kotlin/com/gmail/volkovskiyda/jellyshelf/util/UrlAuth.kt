package com.gmail.volkovskiyda.jellyshelf.util

import java.net.URLEncoder

// Thumbnail URLs are persisted without credentials; the api key is appended only at display
// time. These helpers keep that contract in one place.

/** True for URLs shaped like a Jellyfin image endpoint (vs. an index/YouTube CDN thumbnail). */
fun isJellyfinImageUrl(url: String): Boolean = "/Items/" in url && "/Images/" in url

/**
 * Appends [apiKey] to a Jellyfin-hosted image [url] at display time; other URLs pass through.
 * The key is attached only to URLs on the configured [serverUrl]: index thumbnails are remote
 * input, so a URL merely *shaped* like a Jellyfin image path must never receive the server's
 * credential — it could point anywhere.
 */
fun authorizedImageUrl(url: String?, serverUrl: String?, apiKey: String?): String? {
    if (url.isNullOrBlank() || apiKey.isNullOrBlank()) return url
    if (!isJellyfinImageUrl(url) || "api_key=" in url) return url
    val base = serverUrl?.trim()?.removeSuffix("/")
    if (base.isNullOrBlank() || !url.startsWith("$base/")) return url
    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
}

/**
 * Query parameter names that carry a Jellyfin credential, lowercased. `api_key` is what this app
 * appends and what Jellyfin's own web client uses; the others are spellings Jellyfin also accepts,
 * so a URL that arrived from elsewhere can carry them.
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
