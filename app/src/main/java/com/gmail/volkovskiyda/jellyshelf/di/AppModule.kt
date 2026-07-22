package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultJellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.DefaultSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncWorker
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.MainViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryVideosViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsCache
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsViewModel
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * The whole app's Koin graph. Consumer definitions use the constructor-reference DSL (`singleOf`,
 * `viewModelOf`, `workerOf`) so [com.gmail.volkovskiyda.jellyshelf.di.AppModuleVerifyTest] can
 * introspect and verify every wiring; only the third-party leaf providers below (which need
 * builders or Android context) are plain lambdas. Repository implementations are bound to their
 * domain interfaces, so nothing above the data layer sees a `Default*` type.
 */
val appModule = module {
    single { Moshi.Builder().build() }
    single { provideOkHttpClient(androidContext()) }
    single { provideDatabase(androidContext()) }
    // One process-lifetime scope for app-wide background work (settings snapshots, the Categories
    // selection restore). Repositories that own long-running work create their own scopes.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    singleOf(::DefaultSettingsRepository) { bind<SettingsRepository>() }
    singleOf(::DefaultScrollPositionRepository) { bind<ScrollPositionRepository>() }
    singleOf(::AppSettingsState)
    singleOf(::JellyfinClient)
    singleOf(::JellyfinDataSource)
    singleOf(::DefaultJellyfinRepository) { bind<JellyfinRepository>() }
    singleOf(::YtDlpMetadataSource)
    singleOf(::DefaultLibraryRepository) { bind<LibraryRepository>() }

    // Process-lifetime UI state that must survive tab switches (which clear tab ViewModels).
    singleOf(::LibraryFilterState)
    singleOf(::CategoriesFilterState)
    singleOf(::SettingsCache)

    viewModelOf(::MainViewModel)
    viewModelOf(::LibraryViewModel)
    viewModelOf(::CategoriesViewModel)
    viewModelOf(::CategoryVideosViewModel)
    viewModelOf(::DetailViewModel)
    viewModelOf(::SettingsViewModel)

    workerOf(::SyncWorker)
}

// Log request URLs only in debuggable builds — production must not write every Jellyfin/index URL
// to logcat.
private fun provideOkHttpClient(context: Context): OkHttpClient = OkHttpClient.Builder()
    .apply {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
    }
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

// No destructive fallback: manual categories and in-app yt-dlp metadata are user-authored and not
// reconstructible, so future schema bumps must ship explicit migrations.
private fun provideDatabase(context: Context): JellyshelfDatabase = Room.databaseBuilder(
    context,
    JellyshelfDatabase::class.java,
    "jellyshelf.db",
).build()
