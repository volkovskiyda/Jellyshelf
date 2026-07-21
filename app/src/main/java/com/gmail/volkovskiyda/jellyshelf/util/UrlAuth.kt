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

/** Drops an embedded api_key query parameter from a legacy stored [url]. */
fun stripApiKey(url: String?): String? {
    if (url.isNullOrBlank() || "api_key=" !in url) return url
    val base = url.substringBefore('?')
    val params = url.substringAfter('?', "")
        .split('&')
        .filter { it.isNotBlank() && !it.startsWith("api_key=") }
    return if (params.isEmpty()) base else params.joinToString("&", prefix = "$base?")
}
