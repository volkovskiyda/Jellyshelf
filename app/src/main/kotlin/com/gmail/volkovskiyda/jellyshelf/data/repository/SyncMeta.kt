package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX

// The feed half of a sync: what the metadata API and the index said about one video, combined
// into the single candidate [mergeVideo] weighs against the local row.

/**
 * The constants every row of one sync pass shares: where the server lives, when the pass started,
 * and whether the metadata index and the metadata API were reachable at all this time.
 */
internal data class SyncMergeContext(
    val serverBase: String,
    val now: Long,
    val indexAvailable: Boolean,
    val apiAvailable: Boolean = false,
)

/**
 * One video's metadata candidate for this sync: the entry the feeds produced between them, and
 * the METADATA_SOURCE_* label that combination earns.
 */
internal class SyncMeta(val entry: IndexEntry, val source: String)

/**
 * Combines the metadata API's entry with the index's for one video, per field, newest extraction
 * first: the fresher of the two wins every field it actually carries, and the older one fills what
 * the fresher lacks — so recency and completeness both win, and neither feed can null out a value
 * the other has. A missing [IndexEntry.fetchedAt] counts as oldest.
 */
internal fun combinedMeta(api: IndexEntry?, index: IndexEntry?): SyncMeta? = when {
    api == null && index == null -> null
    api == null -> SyncMeta(index!!, METADATA_SOURCE_INDEX)
    index == null -> SyncMeta(api, METADATA_SOURCE_API)
    else -> {
        val (newer, older) = if ((api.fetchedAt ?: 0L) >= (index.fetchedAt ?: 0L)) {
            api to index
        } else {
            index to api
        }
        SyncMeta(newer.filledFrom(older), METADATA_SOURCE_API_INDEX)
    }
}

/** Every field this entry lacks, taken from [older] — the fill half of [combinedMeta]. */
private fun IndexEntry.filledFrom(older: IndexEntry): IndexEntry = IndexEntry(
    id = id,
    title = title ?: older.title,
    channel = channel ?: older.channel,
    channelId = channelId ?: older.channelId,
    duration = duration ?: older.duration,
    uploadDate = uploadDate ?: older.uploadDate,
    uploadTimestamp = uploadTimestamp ?: older.uploadTimestamp,
    tags = tags ?: older.tags,
    categories = categories ?: older.categories,
    description = description ?: older.description,
    thumbnail = thumbnail ?: older.thumbnail,
    chapters = chapters ?: older.chapters,
    fetchedAt = fetchedAt ?: older.fetchedAt,
)
