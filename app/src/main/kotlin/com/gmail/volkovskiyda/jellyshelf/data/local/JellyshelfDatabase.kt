package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        VideoEntity::class,
        CategoryEntity::class,
        VideoCategoryCrossRef::class,
        SkippedVideoEntity::class,
    ],
    // 2: dropped the videos.durationSeconds index with the library's duration filter, its only
    // reader. Schemas are checked in under app/schemas, so every bump is diffable against the one
    // before it.
    // 3: added videos.lastPlayedAt and its index for the "Last played" filter.
    // 4: added the nullable videos.uploadTimestamp — the first bump carried by a migration rather
    // than a destructive fallback (see provideDatabase). Generated from the two checked-in
    // schemas, so there is no hand-written SQL to drift from them; MigrationInstrumentedTest
    // walks a v3 file through it.
    // 5: added the skipped_videos table (the Now playing skip list), by auto migration as well.
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
@TypeConverters(Converters::class)
abstract class JellyshelfDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun categoryDao(): CategoryDao
    abstract fun skipDao(): SkipDao
}
