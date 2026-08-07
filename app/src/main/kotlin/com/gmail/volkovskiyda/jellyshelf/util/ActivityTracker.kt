package com.gmail.volkovskiyda.jellyshelf.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** Room for the two or three creations a sign-in round trip produces before anyone collects. */
private const val CREATION_BUFFER = 8

/**
 * Which activity is in front, and what has just been created — the two facts a flow that leaves the
 * app and comes back needs, and neither of which a `Context` can answer.
 *
 * Registered once in [com.gmail.volkovskiyda.jellyshelf.JellyshelfApplication]. Firebase's own SDK
 * keeps exactly this (its `FirebaseAppDistributionLifecycleNotifier`) for exactly this reason; ours
 * exists because that one is internal.
 *
 * The current activity is held **weakly**: this is a process-lifetime singleton, and a strong
 * reference to a destroyed activity is the textbook leak.
 */
class ActivityTracker : Application.ActivityLifecycleCallbacks {

    private var resumed: WeakReference<Activity>? = null

    private val _resumes = MutableStateFlow(0)

    /**
     * Incremented on every resume. A counter rather than a boolean or an event, so a caller can
     * mark the value before leaving and wait for one *greater* than it — which is unambiguous even
     * if the resume lands before the wait starts.
     */
    val resumes: StateFlow<Int> = _resumes.asStateFlow()

    private val _created = MutableSharedFlow<String>(extraBufferCapacity = CREATION_BUFFER)

    /**
     * Fully-qualified class names of activities as they are created.
     *
     * Names rather than instances so a caller can watch for a class it cannot reference: the App
     * Distribution SDK's `SignInResultActivity` exists only in release builds, and the debug
     * variant links the API-only stub that does not contain it.
     */
    val created: SharedFlow<String> = _created.asSharedFlow()

    /**
     * The foreground activity, or null when none is. Needed to launch a Custom Tab *into this
     * app's task* — an application context forces `FLAG_ACTIVITY_NEW_TASK`, which is precisely the
     * behaviour [com.gmail.volkovskiyda.jellyshelf.data.remote.TesterSignInLauncher] exists to
     * avoid.
     */
    fun current(): Activity? = resumed?.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        _created.tryEmit(activity.javaClass.name)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        _resumes.value += 1
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
