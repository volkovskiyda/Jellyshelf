package com.gmail.volkovskiyda.jellyshelf.data.mapper

import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount as RoomCategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video

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
    tags = tags,
    youtubeCategories = youtubeCategories,
    thumbnailUrl = thumbnailUrl,
    played = played,
    playbackPositionTicks = playbackPositionTicks,
    playCount = playCount,
    lastSyncedAt = lastSyncedAt,
    metadataSource = metadataSource,
    metadataUpdatedAt = metadataUpdatedAt,
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
