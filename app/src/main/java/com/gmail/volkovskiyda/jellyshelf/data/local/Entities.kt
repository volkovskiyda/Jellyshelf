package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val CATEGORY_TYPE_AUTO_CHANNEL = "AUTO_CHANNEL"
const val CATEGORY_TYPE_AUTO_YEAR = "AUTO_YEAR"
const val CATEGORY_TYPE_AUTO_MONTH = "AUTO_MONTH"
const val CATEGORY_TYPE_AUTO_DURATION = "AUTO_DURATION"
const val CATEGORY_TYPE_AUTO_YT_CATEGORY = "AUTO_YT_CATEGORY"
const val CATEGORY_TYPE_MANUAL = "MANUAL"

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val youtubeId: String,
    val jellyfinItemId: String?,
    val fileName: String,
    val title: String,
    val channel: String?,
    val channelId: String?,
    val durationSeconds: Long,
    val uploadDate: String?,
    val description: String?,
    val tags: List<String>,
    val youtubeCategories: List<String>,
    val thumbnailUrl: String?,
    val played: Boolean,
    val playbackPositionTicks: Long,
    val playCount: Int,
    val lastSyncedAt: Long,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val createdAt: Long,
)

@Entity(
    tableName = "video_category",
    primaryKeys = ["youtubeId", "categoryId"],
    indices = [Index("categoryId")],
)
data class VideoCategoryCrossRef(
    val youtubeId: String,
    val categoryId: String,
)

/** A category row plus its member count, for the categories list. */
data class CategoryWithCount(
    @Embedded val category: CategoryEntity,
    val videoCount: Int,
)
