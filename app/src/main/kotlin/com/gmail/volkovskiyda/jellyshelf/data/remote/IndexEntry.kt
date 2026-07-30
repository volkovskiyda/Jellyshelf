package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
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
    /** Structured yt-dlp chapters; null/empty for videos without them (older indexes too). */
    @SerialName("chapters") val chapters: List<IndexChapter>? = null,
    /** yt-dlp's extraction time (its `epoch`, in seconds), when the script emits it. Drives newest-wins. */
    @SerialName("fetchedAt") val fetchedAt: Long? = null,
)

/**
 * One structured chapter as the index (and the in-app yt-dlp fetch) carries it: yt-dlp's
 * `start_time` is a float of seconds. Kept as its own wire type so the JSON shape can stay
 * loose while [toChapters] owns the tightening into the domain [Chapter].
 */
@Serializable
data class IndexChapter(
    @SerialName("start") val startSeconds: Double? = null,
    @SerialName("title") val title: String? = null,
)

private const val MILLIS_PER_SECOND = 1_000

/**
 * Wire chapters → domain [Chapter]s: milliseconds, non-blank titles only, entries with a
 * missing or negative start dropped, and sorted ascending — the player's current-chapter
 * lookup assumes order, and remote data doesn't get to break that assumption.
 */
internal fun List<IndexChapter>?.toChapters(): List<Chapter> = orEmpty()
    .mapNotNull { chapter ->
        val startSeconds = chapter.startSeconds?.takeIf { it >= 0 } ?: return@mapNotNull null
        val title = chapter.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Chapter(startMs = (startSeconds * MILLIS_PER_SECOND).toLong(), title = title)
    }
    .sortedBy(Chapter::startMs)
