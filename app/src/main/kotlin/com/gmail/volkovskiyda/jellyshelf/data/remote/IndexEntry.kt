package com.gmail.volkovskiyda.jellyshelf.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One record from the aggregated yt-dlp metadata index (jellyshelf-index.json),
 * produced on the server by build-library-index.sh from the .info.json sidecars.
 * Fetched over HTTP as a flat array and joined to Jellyfin items by [id].
 */
@Serializable
data class IndexEntry(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("channel") val channel: String? = null,
    @SerialName("channelId") val channelId: String? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("uploadDate") val uploadDate: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("categories") val categories: List<String>? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
    /** yt-dlp's extraction time (its `epoch`, in seconds), when the script emits it. Drives newest-wins. */
    @SerialName("fetchedAt") val fetchedAt: Long? = null,
)
