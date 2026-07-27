package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.util.fileNameFromPath
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds

/**
 * Pure merge of one Jellyfin [item] with its optional index entry [meta] and the [existing]
 * local row, deciding whose metadata wins:
 *  - a prior in-app yt-dlp fetch is kept unless the index carries a genuinely newer extraction;
 *  - an index-sourced row keeps its metadata when [indexAvailable] is false (the index could not
 *    be fetched this sync), so a transient failure never downgrades it to bare Jellyfin fields —
 *    only a successfully fetched index that lacks the entry does;
 *  - otherwise the row is rebuilt from the index entry, falling back to Jellyfin item fields.
 *
 * Jellyfin-owned fields (item id, file name, watch state) refresh from [item] — except when
 * [keepLocalWatchState] is set, meaning this device wrote the row's watch state after the
 * server snapshot in [item] was taken, so the local values are the newer ones.
 */
internal fun mergeVideo(
    existing: VideoEntity?,
    youtubeId: String,
    item: BaseItemDto,
    meta: IndexEntry?,
    indexAvailable: Boolean,
    serverBase: String,
    now: Long,
    keepLocalWatchState: Boolean = false,
): VideoEntity {
    val indexUpdatedAt = meta?.fetchedAt?.let { it * 1000 } // epoch seconds -> millis
    val retainedWatch = existing?.takeIf { keepLocalWatchState }
    val played = retainedWatch?.played ?: item.userData?.played ?: false
    val positionTicks =
        retainedWatch?.playbackPositionTicks ?: item.userData?.playbackPositionTicks ?: 0L
    val playCount = retainedWatch?.playCount ?: item.userData?.playCount ?: 0

    val fileName = fileNameFromPath(item.path)
        ?: (meta?.title ?: existing?.title ?: item.name ?: youtubeId)

    val keepExisting = when (existing?.metadataSource) {
        METADATA_SOURCE_YTDLP ->
            meta == null || indexUpdatedAt == null || indexUpdatedAt <= existing.metadataUpdatedAt
        METADATA_SOURCE_INDEX -> meta == null && !indexAvailable
        else -> false
    }

    if (keepExisting) {
        return existing!!.copy(
            jellyfinItemId = item.id,
            fileName = fileName,
            // Legacy rows persisted the api key inside the URL; scrub it on the way through.
            thumbnailUrl = stripCredentials(existing.thumbnailUrl),
            played = played,
            playbackPositionTicks = positionTicks,
            playCount = playCount,
            lastSyncedAt = now,
            // Seen on the server again — any grace-period misses it accumulated are void.
            missedSyncs = 0,
        )
    }

    val metadataSource = if (meta != null) METADATA_SOURCE_INDEX else METADATA_SOURCE_JELLYFIN
    // A recorded yt-dlp failure only means anything while the video is still missing metadata, so
    // carry it across syncs (this branch rebuilds the row from scratch and would otherwise erase
    // it every sync, hiding exactly the persistent failures it exists to report) — but drop it the
    // moment the index supplies what the fetch was after.
    val keepFetchError = metadataSource == METADATA_SOURCE_JELLYFIN

    return VideoEntity(
        youtubeId = youtubeId,
        jellyfinItemId = item.id,
        fileName = fileName,
        title = meta?.title ?: item.name ?: youtubeId,
        channel = meta?.channel,
        channelId = meta?.channelId,
        durationSeconds = meta?.duration
            ?: item.runTimeTicks?.let { ticksToSeconds(it) }
            ?: 0L,
        uploadDate = meta?.uploadDate ?: item.productionYear?.toString(),
        description = meta?.description ?: item.overview,
        tags = meta?.tags ?: item.tags ?: emptyList(),
        youtubeCategories = meta?.categories ?: item.genres ?: emptyList(),
        // Stored without credentials; the UI appends the current api key when loading
        // Jellyfin-hosted images, so key rotation can't strand stale URLs in the database.
        thumbnailUrl = meta?.thumbnail ?: "$serverBase/Items/${item.id}/Images/Primary?maxWidth=480",
        played = played,
        playbackPositionTicks = positionTicks,
        playCount = playCount,
        lastSyncedAt = now,
        metadataSource = metadataSource,
        metadataUpdatedAt = if (meta != null) (indexUpdatedAt ?: now) else 0L,
        lastFetchError = existing?.lastFetchError.takeIf { keepFetchError },
        lastFetchErrorAt = if (keepFetchError) existing?.lastFetchErrorAt ?: 0L else 0L,
    )
}
