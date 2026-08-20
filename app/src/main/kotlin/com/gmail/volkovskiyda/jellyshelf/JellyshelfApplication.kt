package com.gmail.volkovskiyda.jellyshelf

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateFlags
import com.gmail.volkovskiyda.jellyshelf.di.appModule
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.util.ActivityTracker
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
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
        // Registered before anything can navigate, so the tracker never misses the first activity.
        // The tester sign-in needs a foreground Activity to open its Custom Tab into this app's
        // task, and needs to see the redirect land to close it again — neither is answerable from
        // an application context. See TesterSignInLauncher.
        registerActivityLifecycleCallbacks(get<ActivityTracker>())
        // Read after Koin starts so the flag comes from the injected BuildInfo (the single source
        // of truth), and shared by the two gates below rather than resolved from the graph twice.
        val isDebug = get<BuildInfo>().isDebug
        // Release plants no tree, so every Timber call is a no-op at runtime — and R8's
        // -assumenosideeffects rules (src/main/keepRules) strip the calls from release bytecode
        // entirely.
        if (isDebug) {
            Timber.plant(Timber.DebugTree())
        }
        // The inverse gate: only release builds report crashes and perf traces, so the dashboards
        // describe real usage — development crashes never dilute the crash-free-users metric and
        // dev/emulator runs never pollute the performance trends. Both SDKs persist this flag, so
        // setting it on every start is what keeps a build that changes type from inheriting the
        // previous answer.
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!isDebug)
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = !isDebug
        // The cold-start update check, here rather than in a composable's LaunchedEffect(Unit):
        // onCreate runs exactly once per process, while "once per composition" is a weaker promise
        // than it looks under configuration changes and the theme reveal's uiMode handling. It
        // returns immediately unless a release build has opted into a channel, and never blocks —
        // it only launches work on the application scope. UpdateChecker documents every gate.
        get<UpdateFlags>().refresh()
        get<UpdateChecker>().checkOnStart()
        // And the daily one, which the launch check cannot stand in for: the whole point is the
        // user who does not launch. Unlike periodic sync below, re-stating this every start is
        // correct — it is cancelled by turning the channel off, which is persisted state this
        // reads back and honours, not by an act it would undo.
        get<UpdateChecker>().scheduleBackgroundCheck()
        // Periodic sync is deliberately NOT scheduled here: WorkManager persists it across
        // launches, and re-scheduling on every start would undo the wipe Sign out performs, which
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
