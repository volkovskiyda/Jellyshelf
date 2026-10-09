package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks a real database file through every migration the app ships, against the exported schemas
 * under app/schemas. The point is the user-authored data a destructive fallback would cost —
 * manual categories, in-app yt-dlp metadata — so each case seeds a row the old way, migrates, and
 * reads it back through the current schema.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JellyshelfDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate3To4_keepsEveryRowAndAddsAnEmptyUploadTimestamp() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO videos (youtubeId, jellyfinItemId, fileName, title, channel, channelId, " +
                    "durationSeconds, uploadDate, description, chapters, tags, youtubeCategories, " +
                    "thumbnailUrl, played, playbackPositionTicks, playCount, lastPlayedAt, lastSyncedAt, " +
                    "metadataSource, metadataUpdatedAt, missedSyncs, lastFetchError, lastFetchErrorAt) " +
                    "VALUES ('v1', 'jf-1', 'v1.mp4', 'Title', 'Channel', 'UC1', 100, '20240101', 'desc', " +
                    "'[]', '[\"a\"]', '[]', NULL, 0, 0, 0, 0, 1, 'YTDLP', 0, 0, NULL, 0)",
            )
            db.execSQL("INSERT INTO categories (id, name, type, createdAt) VALUES ('m1', 'Mine', 'MANUAL', 1)")
            db.execSQL("INSERT INTO video_category (youtubeId, categoryId) VALUES ('v1', 'm1')")
        }

        // Validates the migrated schema against 4.json — a column the auto migration missed, or an
        // index it dropped, fails here rather than at the first query in production.
        helper.runMigrationsAndValidate(DB_NAME, 4, true).use { db ->
            db.query("SELECT youtubeId, uploadDate, uploadTimestamp FROM videos").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("v1", cursor.getString(0))
                assertEquals("20240101", cursor.getString(1))
                assertNull(cursor.getString(2))
            }
            db.query("SELECT COUNT(*) FROM video_category WHERE youtubeId = 'v1' AND categoryId = 'm1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}

private const val DB_NAME = "migration-test.db"
