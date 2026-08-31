package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        VideoEntity::class,
        CategoryEntity::class,
        VideoCategoryCrossRef::class,
    ],
    // 2: dropped the videos.durationSeconds index with the library's duration filter, its only
    // reader. Schemas are checked in under app/schemas, so every bump is diffable against the one
    // before it.
    // 3: added videos.lastPlayedAt and its index for the "Last played" filter.
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class JellyshelfDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun categoryDao(): CategoryDao
}
