package com.gmail.volkovskiyda.jellyshelf

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.gmail.volkovskiyda.jellyshelf.di.appModule
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import timber.log.Timber

class JellyshelfApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@JellyshelfApplication)
            // Register the Koin worker factory so workers declared with workerOf(...) get
            // constructor injection. The default WorkManager initializer is removed in the
            // manifest so this is the sole initialization path (see AndroidManifest.xml).
            workManagerFactory()
            modules(appModule)
        }
        // Read after Koin starts so the flag comes from the injected BuildInfo (the single source
        // of truth), and shared by the two gates below rather than resolved from the graph twice.
        val isDebug = get<BuildInfo>().isDebug
        // Release plants no tree, so every Timber call is a no-op at runtime — and R8's
        // -assumenosideeffects rules (src/main/keepRules) strip the calls from release bytecode
        // entirely.
        if (isDebug) {
            Timber.plant(Timber.DebugTree())
        }
        // The inverse gate: only release builds report crashes, so the dashboard describes real
        // usage and development crashes never dilute the crash-free-users metric. Crashlytics
        // persists this flag, so setting it on every start is what keeps a build that changes type
        // from inheriting the previous answer.
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!isDebug)
        // Periodic sync is deliberately NOT scheduled here: WorkManager persists it across
        // launches, and re-scheduling on every start would undo "Reset local data", which
        // cancels it. "Sync now" owns creating it (see SyncScheduler).
    }

    /**
     * Coil's singleton hook: every `AsyncImage` resolves its loader through here, so the tuned
     * client in [com.gmail.volkovskiyda.jellyshelf.di.appModule] applies without threading an
     * `ImageLoader` through the composables. Called lazily on the first image request, long after
     * [startKoin], so resolving from the graph here is safe.
     */
    override fun newImageLoader(): ImageLoader = get()
}
