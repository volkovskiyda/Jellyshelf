package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One record from the aggregated yt-dlp metadata index (jellyshelf-index.json),
 * produced on the server by build-library-index.sh from the .info.json sidecars.
 * Fetched over HTTP as a flat array and joined to Jellyfin items by [id].
 */
@JsonClass(generateAdapter = true)
data class IndexEntry(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String? = null,
    @Json(name = "channel") val channel: String? = null,
    @Json(name = "channelId") val channelId: String? = null,
    @Json(name = "duration") val duration: Long? = null,
    @Json(name = "uploadDate") val uploadDate: String? = null,
    @Json(name = "tags") val tags: List<String>? = null,
    @Json(name = "categories") val categories: List<String>? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "thumbnail") val thumbnail: String? = null,
    /** yt-dlp's extraction time (its `epoch`, in seconds), when the script emits it. Drives newest-wins. */
    @Json(name = "fetchedAt") val fetchedAt: Long? = null,
)
