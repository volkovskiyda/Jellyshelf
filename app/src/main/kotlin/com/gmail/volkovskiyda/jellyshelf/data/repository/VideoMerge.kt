package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.toChapters
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_API_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.util.fileNameFromPath
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds

private const val MILLIS_PER_SECOND = 1000L

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
    tags = tags ?: older.tags,
    categories = categories ?: older.categories,
    description = description ?: older.description,
    thumbnail = thumbnail ?: older.thumbnail,
    chapters = chapters ?: older.chapters,
    fetchedAt = fetchedAt ?: older.fetchedAt,
)

/** Watch state for one row: whichever of the local row and the server snapshot is newer. */
private class WatchState(val played: Boolean, val positionTicks: Long, val playCount: Int)

/**
 * The per-row values both merge branches write, resolved once before the keep-or-rebuild decision.
 */
private class ResolvedRow(val youtubeId: String, val fileName: String, val watch: WatchState)

/**
 * The index entry's extraction time as epoch millis — the feed carries seconds. Also used by the
 * demo seeder, which stamps its rows exactly as an index match would.
 */
internal val IndexEntry?.updatedAtMillis: Long? get() = this?.fetchedAt?.let { it * MILLIS_PER_SECOND }

/**
 * Pure merge of one Jellyfin [item] with its optional feed metadata [meta] (the API and index
 * entries already combined by [combinedMeta]) and the [existing] local row, deciding whose
 * metadata wins — newest extraction wins, per source:
 *  - a prior in-app yt-dlp fetch is kept unless the feeds carry a genuinely newer extraction;
 *  - a feed-sourced row keeps its metadata over an equal-or-older candidate, and keeps it outright
 *    when its own feed(s) could not be fetched this sync ([SyncMergeContext.indexAvailable] /
 *    [SyncMergeContext.apiAvailable]), so a transient failure never downgrades it to bare
 *    Jellyfin fields — only a successfully fetched feed that lacks the entry does;
 *  - otherwise the row is rebuilt from the candidate entry, falling back to Jellyfin item fields.
 *
 * Jellyfin-owned fields (item id, file name, watch state) refresh from [item] — except when
 * [keepLocalWatchState] is set, meaning this device wrote the row's watch state after the
 * server snapshot in [item] was taken, so the local values are the newer ones.
 */
internal fun mergeVideo(
    existing: VideoEntity?,
    youtubeId: String,
    item: BaseItemDto,
    meta: SyncMeta?,
    context: SyncMergeContext,
    keepLocalWatchState: Boolean = false,
): VideoEntity {
    val row = ResolvedRow(
        youtubeId = youtubeId,
        fileName = fileNameFromPath(item.path)
            ?: (meta?.entry?.title ?: existing?.title ?: item.name ?: youtubeId),
        watch = resolveWatchState(existing, item, keepLocalWatchState),
    )

    if (keepsExistingMetadata(existing, meta, context)) {
        return refreshedExisting(existing!!, item, row, context.now)
    }
    return rebuiltEntity(existing, item, meta, row, context)
}

/** The watch state that wins: a local write newer than the server snapshot, else the snapshot. */
private fun resolveWatchState(
    existing: VideoEntity?,
    item: BaseItemDto,
    keepLocalWatchState: Boolean,
): WatchState {
    val retained = existing?.takeIf { keepLocalWatchState }
    return WatchState(
        played = retained?.played ?: item.userData?.played ?: false,
        positionTicks = retained?.playbackPositionTicks ?: item.userData?.playbackPositionTicks ?: 0L,
        playCount = retained?.playCount ?: item.userData?.playCount ?: 0,
    )
}

/**
 * Whether [existing] keeps its own metadata instead of being rebuilt from [meta] — the
 * newest-wins half of [mergeVideo]'s contract, per metadata source.
 */
private fun keepsExistingMetadata(
    existing: VideoEntity?,
    meta: SyncMeta?,
    context: SyncMergeContext,
): Boolean = when (existing?.metadataSource) {
    METADATA_SOURCE_YTDLP,
    METADATA_SOURCE_INDEX,
    METADATA_SOURCE_API,
    METADATA_SOURCE_API_INDEX,
    -> {
        if (meta == null) {
            // No candidate this sync. A yt-dlp row has nothing to be rebuilt from and always
            // stays; a feed-sourced row is downgraded only when every feed that vouched for it
            // was reachable and genuinely lacks the entry — a transient outage never demotes.
            existing.metadataSource == METADATA_SOURCE_YTDLP ||
                !sourceFeedsAvailable(existing.metadataSource, context)
        } else {
            val metaUpdatedAt = meta.entry.updatedAtMillis
            metaUpdatedAt == null || metaUpdatedAt <= existing.metadataUpdatedAt
        }
    }
    else -> false
}

/** Whether every feed that produced metadata of [source] was reachable this sync. */
private fun sourceFeedsAvailable(source: String, context: SyncMergeContext): Boolean =
    when (source) {
        METADATA_SOURCE_INDEX -> context.indexAvailable
        METADATA_SOURCE_API -> context.apiAvailable
        else -> context.indexAvailable && context.apiAvailable
    }

/** Keeps [existing]'s metadata untouched, refreshing only the fields Jellyfin owns. */
private fun refreshedExisting(
    existing: VideoEntity,
    item: BaseItemDto,
    row: ResolvedRow,
    now: Long,
): VideoEntity = existing.copy(
    jellyfinItemId = item.id,
    fileName = row.fileName,
    // Legacy rows persisted the api key inside the URL; scrub it on the way through.
    thumbnailUrl = stripCredentials(existing.thumbnailUrl),
    played = row.watch.played,
    playbackPositionTicks = row.watch.positionTicks,
    playCount = row.watch.playCount,
    lastSyncedAt = now,
    // Seen on the server again — any grace-period misses it accumulated are void.
    missedSyncs = 0,
)

/** Where a rebuilt row's metadata came from, and the yt-dlp fetch error it carries forward. */
private class Provenance(
    val source: String,
    val updatedAt: Long,
    val fetchError: String?,
    val fetchErrorAt: Long,
)

/** Resolves the provenance fields of a rebuilt row — its source, its age, its carried failure. */
private fun resolveProvenance(existing: VideoEntity?, meta: SyncMeta?, now: Long): Provenance {
    val source = meta?.source ?: METADATA_SOURCE_JELLYFIN
    // A recorded yt-dlp failure only means anything while the video is still missing metadata, so
    // carry it across syncs (the rebuild branch starts from scratch and would otherwise erase it
    // every sync, hiding exactly the persistent failures it exists to report) — but drop it the
    // moment a feed supplies what the fetch was after.
    val keepFetchError = source == METADATA_SOURCE_JELLYFIN
    return Provenance(
        source = source,
        updatedAt = if (meta != null) (meta.entry.updatedAtMillis ?: now) else 0L,
        fetchError = existing?.lastFetchError.takeIf { keepFetchError },
        fetchErrorAt = if (keepFetchError) existing?.lastFetchErrorAt ?: 0L else 0L,
    )
}

/** Rebuilds the row from the candidate entry where there is one, falling back to Jellyfin's fields. */
private fun rebuiltEntity(
    existing: VideoEntity?,
    item: BaseItemDto,
    syncMeta: SyncMeta?,
    row: ResolvedRow,
    context: SyncMergeContext,
): VideoEntity {
    val provenance = resolveProvenance(existing, syncMeta, context.now)
    val meta = syncMeta?.entry

    return VideoEntity(
        youtubeId = row.youtubeId,
        jellyfinItemId = item.id,
        fileName = row.fileName,
        played = row.watch.played,
        playbackPositionTicks = row.watch.positionTicks,
        playCount = row.watch.playCount,
        lastSyncedAt = context.now,
        metadataSource = provenance.source,
        metadataUpdatedAt = provenance.updatedAt,
        lastFetchError = provenance.fetchError,
        lastFetchErrorAt = provenance.fetchErrorAt,
        title = meta?.title ?: item.name ?: row.youtubeId,
        channel = meta?.channel,
        channelId = meta?.channelId,
        durationSeconds = meta?.duration ?: item.runTimeTicks?.let { ticksToSeconds(it) } ?: 0L,
        uploadDate = meta?.uploadDate ?: item.productionYear?.toString(),
        description = meta?.description ?: item.overview,
        // Jellyfin has no chapter concept for these files, so unlike description there is no
        // item fallback — no index entry simply means no structured chapters.
        chapters = meta?.chapters.toChapters(),
        tags = meta?.tags ?: item.tags ?: emptyList(),
        youtubeCategories = meta?.categories ?: item.genres ?: emptyList(),
        // Stored without credentials; the UI appends the current api key when loading
        // Jellyfin-hosted images, so key rotation can't strand stale URLs in the database.
        thumbnailUrl = meta?.thumbnail
            ?: "${context.serverBase}/Items/${item.id}/Images/Primary?maxWidth=480",
    )
}
