package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.Settings
import com.gmail.volkovskiyda.jellyshelf.data.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsCache
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** Lightweight manual DI graph, owned by [JellyshelfApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val moshi: Moshi = Moshi.Builder().build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .apply {
            // Log request URLs only in debuggable builds — production must not write every
            // Jellyfin/index URL to logcat.
            val debuggable =
                (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // No destructive fallback: manual categories and in-app yt-dlp metadata are user-authored
    // and not reconstructible, so future schema bumps must ship explicit migrations.
    private val database: JellyshelfDatabase = Room.databaseBuilder(
        appContext,
        JellyshelfDatabase::class.java,
        "jellyshelf.db",
    ).build()

    val settingsRepository = SettingsRepository(appContext)

    /**
     * App-wide settings snapshot for cheap synchronous reads from composition (e.g. appending
     * the api key to thumbnail URLs at display time); null until the first DataStore read lands.
     */
    val settingsState: StateFlow<Settings?> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, null)

    val scrollPositionRepository = ScrollPositionRepository(appContext)

    // Per-process UI state that must survive tab switches, which clear tab ViewModels.
    val libraryFilterState = LibraryFilterState()
    val categoriesFilterState = CategoriesFilterState()
    val settingsCache = SettingsCache()

    private val jellyfinClient = JellyfinClient(okHttpClient, moshi)

    val jellyfinRepository = JellyfinRepository(jellyfinClient, okHttpClient, moshi)

    private val ytDlpMetadataSource = YtDlpMetadataSource(appContext)

    val libraryRepository =
        LibraryRepository(database, jellyfinRepository, settingsRepository, ytDlpMetadataSource)

    init {
        // Restore the last-viewed Categories dimension so reopening the app lands on it rather
        // than the first tab. Async, best-effort: don't overwrite a selection the user already
        // made this session before the read landed, and flip selectionLoaded either way so the
        // UI stops deferring pager tracking.
        appScope.launch {
            val persisted = settingsRepository.selectedCategoryType.first()
            if (persisted != null && categoriesFilterState.selectedType.value == null) {
                categoriesFilterState.selectedType.value = persisted
            }
            categoriesFilterState.selectionLoaded.value = true
        }
    }
}
