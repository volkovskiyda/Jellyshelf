package com.gmail.volkovskiyda.jellyshelf

import android.app.Application
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import timber.log.Timber

class JellyshelfApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Log only in debug builds. Release plants no tree, so every Timber call is a no-op at
        // runtime — and R8's -assumenosideeffects rules (src/main/keepRules) strip the calls from
        // release bytecode entirely.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        startKoin {
            androidContext(this@JellyshelfApplication)
            // Register the Koin worker factory so workers declared with workerOf(...) get
            // constructor injection. The default WorkManager initializer is removed in the
            // manifest so this is the sole initialization path (see AndroidManifest.xml).
            workManagerFactory()
            modules(appModule)
        }
        SyncScheduler.schedulePeriodic(this)
    }
}
