package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The synchronous mirror the activity reads before its first frame. Instrumented because
 * `SharedPreferences` needs a real [Context] — the same reason the DAO and DataStore suites are.
 *
 * What matters here is that a *miss* is harmless: every failure mode has to land on
 * [ThemeMode.AUTO], which is what the app did before a theme setting existed.
 */
@RunWith(AndroidJUnit4::class)
class ThemeModeCacheInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearStoredMode() {
        context.getSharedPreferences("theme_startup", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun everyModeSurvivesARoundTrip() {
        ThemeMode.entries.forEach { mode ->
            ThemeModeCache(context).store(mode)

            // A fresh instance, so the value is read back from disk rather than from memory —
            // the cross-process-restart case this cache exists for.
            assertEquals(mode, ThemeModeCache(context).peek())
        }
    }

    @Test
    fun anEmptyCacheReadsAsAuto() {
        assertEquals(ThemeMode.AUTO, ThemeModeCache(context).peek())
    }

    /** A value written by a future build must not decide the window colour by accident. */
    @Test
    fun anUnrecognizedStoredValueReadsAsAuto() {
        context.getSharedPreferences("theme_startup", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", "midnight")
            .commit()

        assertEquals(ThemeMode.AUTO, ThemeModeCache(context).peek())
    }
}
