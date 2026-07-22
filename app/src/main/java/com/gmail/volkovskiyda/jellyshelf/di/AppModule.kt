package com.gmail.volkovskiyda.jellyshelf.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.gmail.volkovskiyda.jellyshelf.BuildConfig
import com.gmail.volkovskiyda.jellyshelf.data.DefaultDispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
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
    single { BuildInfo(isDebug = BuildConfig.DEBUG, sdkInt = Build.VERSION.SDK_INT) }
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    single { Moshi.Builder().build() }
    single { provideOkHttpClient(get()) }
    single { provideDatabase(androidContext()) }

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

// Log request URLs only in debug builds — release must not write every Jellyfin/index URL to
// logcat.
private fun provideOkHttpClient(buildInfo: BuildInfo): OkHttpClient = OkHttpClient.Builder()
    .apply {
        if (buildInfo.isDebug) {
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
