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
    version = 1,
    // Schemas are checked in under app/schemas so every future version bump ships a real migration
    // in the same commit — manual categories and in-app yt-dlp metadata are user-authored and must
    // survive updates.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class JellyshelfDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun categoryDao(): CategoryDao
}
