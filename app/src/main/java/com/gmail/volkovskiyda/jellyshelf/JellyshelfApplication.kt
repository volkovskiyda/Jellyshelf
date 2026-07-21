package com.gmail.volkovskiyda.jellyshelf

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.di.AppContainer
import timber.log.Timber

class JellyshelfApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Log only in debuggable builds. Release plants no tree, so every Timber call is a no-op
        // at runtime — and R8's -assumenosideeffects rules (src/main/keepRules) strip the calls
        // from release bytecode entirely.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Timber.plant(Timber.DebugTree())
        }
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }
}

/** Convenience accessor for ViewModels: `container.libraryRepository`, etc. */
val AndroidViewModel.container: AppContainer
    get() = (getApplication() as JellyshelfApplication).container
