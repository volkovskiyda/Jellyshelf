package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import androidx.room.Room
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.Settings
import com.gmail.volkovskiyda.jellyshelf.data.repository.SettingsRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** Lightweight manual DI graph, owned by [JellyshelfApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val moshi: Moshi = Moshi.Builder().build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val database: JellyshelfDatabase = Room.databaseBuilder(
        appContext,
        JellyshelfDatabase::class.java,
        "jellyshelf.db",
    ).fallbackToDestructiveMigration(true).build()

    val settingsRepository = SettingsRepository(appContext)

    /**
     * App-wide settings snapshot for cheap synchronous reads from composition (e.g. appending
     * the api key to thumbnail URLs at display time); null until the first DataStore read lands.
     */
    val settingsState: StateFlow<Settings?> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, null)

    val scrollPositionRepository = ScrollPositionRepository(appContext)

    private val jellyfinClient = JellyfinClient(okHttpClient, moshi)

    val jellyfinRepository = JellyfinRepository(jellyfinClient, okHttpClient, moshi)

    private val ytDlpMetadataSource = YtDlpMetadataSource(appContext)

    val libraryRepository =
        LibraryRepository(database, jellyfinRepository, settingsRepository, ytDlpMetadataSource)
}
