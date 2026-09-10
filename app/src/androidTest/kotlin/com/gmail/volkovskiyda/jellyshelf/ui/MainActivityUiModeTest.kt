package com.gmail.volkovskiyda.jellyshelf.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.grantJourneyPermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** How long the flip is given to travel through `UiModeManager` and back into the activity. */
private const val FLIP_TIMEOUT_MS = 10_000L
private const val POLL_INTERVAL_MS = 50L

/**
 * What the manifest's `android:configChanges="uiMode"` buys: a system dark-mode flip repaints the
 * running activity instead of tearing it down. Without it the framework recreates [MainActivity] on
 * every flip — which costs scroll positions and other transient UI state in
 * [AUTO][com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode.AUTO], and achieves nothing at all
 * when the user has forced light or dark.
 *
 * Recreation is counted at the source, through the application's lifecycle callbacks, rather than
 * inferred from [ActivityScenario] — a recreated activity is a *new* instance the scenario hands
 * back just as readily as the old one, so only the creation count tells the two cases apart.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiModeTest {

    /** Every [MainActivity] built during the test, in creation order. */
    private val created = CopyOnWriteArrayList<MainActivity>()

    private val creationWatch = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            (activity as? MainActivity)?.let { created += it }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** The device's own setting, put back in [restoreNightMode] so later suites inherit it intact. */
    private var deviceNightMode = ""

    @Before
    fun watchCreationsAndRememberNightMode() {
        // A library left populated by an earlier test in this suite would have the library screen
        // ask for POST_NOTIFICATIONS, and a signed-out start asks for the local network on API 37;
        // either dialog is a window this test's assertions cannot see past. Granting both up front
        // keeps the night-mode flip the only thing under test.
        grantJourneyPermissions()
        application().registerActivityLifecycleCallbacks(creationWatch)
        deviceNightMode = shell("cmd uimode night").substringAfter("Night mode:", "").trim()
        // Nothing gets flipped that cannot be flipped back: an unreadable setting skips the test
        // rather than leaving the device in whatever state this run happened to want.
        assumeTrue("Could not read the device night mode", deviceNightMode.isNotEmpty())
    }

    @After
    fun restoreNightMode() {
        application().unregisterActivityLifecycleCallbacks(creationWatch)
        if (deviceNightMode.isNotEmpty()) shell("cmd uimode night $deviceNightMode")
    }

    @Test
    fun a_system_night_mode_flip_repaints_instead_of_recreating() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val launched = created.single()
            val startedInNight = nightModeActive(launched)

            shell("cmd uimode night ${if (startedInNight) "no" else "yes"}")

            // The flip is asynchronous, so wait for it to reach whichever activity is live before
            // judging — recreated or not, that one always ends up with the new configuration.
            awaitNightMode(active = !startedInNight)

            assertEquals(
                "MainActivity was recreated by the night-mode change",
                1,
                created.size,
            )
            assertTrue(
                "The surviving MainActivity never picked up the new configuration",
                nightModeActive(launched) == !startedInNight,
            )
        }
    }

    private fun awaitNightMode(active: Boolean) {
        val deadline = SystemClock.uptimeMillis() + FLIP_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (created.last().let(::nightModeActive) == active) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("The night-mode flip to $active never reached the activity")
    }

    /** Read on the main thread, which is the only one the activity's resources are updated from. */
    private fun nightModeActive(activity: Activity): Boolean {
        var active = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            active = activity.resources.configuration.isNightModeActive
        }
        return active
    }

    private fun application(): Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    /** `cmd uimode night [yes|no|auto|…]` both reads ("Night mode: yes") and writes the setting. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes().decodeToString() }
}
