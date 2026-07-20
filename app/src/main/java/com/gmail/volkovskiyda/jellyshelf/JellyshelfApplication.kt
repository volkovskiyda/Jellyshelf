package com.gmail.volkovskiyda.jellyshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.di.AppContainer

class JellyshelfApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }
}

/** Convenience accessor for ViewModels: `container.libraryRepository`, etc. */
val AndroidViewModel.container: AppContainer
    get() = (getApplication() as JellyshelfApplication).container
