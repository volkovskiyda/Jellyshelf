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
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class JellyshelfDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun categoryDao(): CategoryDao
}
