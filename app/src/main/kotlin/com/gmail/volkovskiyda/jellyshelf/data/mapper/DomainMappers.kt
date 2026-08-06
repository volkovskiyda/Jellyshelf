package com.gmail.volkovskiyda.jellyshelf.data.mapper

import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoBrowseRow
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount as RoomCategoryWithCount

// Boundary mappers: the data layer maps its Room rows and network DTOs to domain models so nothing
// above it depends on Room or Moshi. Applied at each repository's public Flow boundary.

fun VideoEntity.toDomain(): Video = Video(
    youtubeId = youtubeId,
    jellyfinItemId = jellyfinItemId,
    fileName = fileName,
    title = title,
    channel = channel,
    channelId = channelId,
    durationSeconds = durationSeconds,
    uploadDate = uploadDate,
    description = description,
    chapters = chapters,
    tags = tags,
    youtubeCategories = youtubeCategories,
    thumbnailUrl = thumbnailUrl,
    played = played,
    playbackPositionTicks = playbackPositionTicks,
    playCount = playCount,
    lastSyncedAt = lastSyncedAt,
    metadataSource = metadataSource,
    metadataUpdatedAt = metadataUpdatedAt,
    missedSyncs = missedSyncs,
    lastFetchError = lastFetchError,
    lastFetchErrorAt = lastFetchErrorAt,
)

/**
 * Maps a projected browse row onto the same [Video] the full-row mapper produces, so a list and a
 * detail screen keep talking about one model.
 *
 * The fields a list row does not need come back empty regardless of what the stored row holds —
 * [Video.description], [Video.chapters], [Video.tags] and [Video.youtubeCategories] are *not
 * loaded*, not *absent*. Anything that needs them must read the video through `observeVideo`.
 *
 * The remaining unprojected fields are filled with their zero values for the same reason, and are
 * equally not to be trusted from a browse emission.
 */
fun VideoBrowseRow.toDomain(): Video = Video(
    youtubeId = youtubeId,
    jellyfinItemId = null,
    fileName = fileName,
    title = title,
    channel = channel,
    channelId = null,
    durationSeconds = durationSeconds,
    uploadDate = uploadDate,
    description = null,
    chapters = emptyList(),
    tags = emptyList(),
    youtubeCategories = emptyList(),
    thumbnailUrl = thumbnailUrl,
    played = played,
    playbackPositionTicks = playbackPositionTicks,
    playCount = 0,
    lastSyncedAt = 0L,
    metadataSource = metadataSource,
    metadataUpdatedAt = 0L,
    missedSyncs = missedSyncs,
    lastFetchError = null,
    lastFetchErrorAt = 0L,
)

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    type = type,
    createdAt = createdAt,
)

fun RoomCategoryWithCount.toDomain(): CategoryWithCount = CategoryWithCount(
    category = category.toDomain(),
    videoCount = videoCount,
)

fun UserDto.toDomain(): User = User(id = id, name = name)

fun BaseItemDto.toMediaFolder(): MediaFolder = MediaFolder(id = id, name = name ?: id, path = path)
