package com.gmail.volkovskiyda.jellyshelf.util

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generous for a same-thread callback; these waits exist to fail loudly, not to pace anything. */
private const val WAIT_MS = 2_000L

/** Short, and expected to elapse: the assertion is that nothing arrives. */
private const val SILENCE_MS = 200L

/**
 * The contract [TesterSignInLauncher][com.gmail.volkovskiyda.jellyshelf.data.remote.TesterSignInLauncher]
 * leans on, driven by hand: the callbacks are called directly with plain [Activity] instances, no
 * launch involved, because the contract is about the bookkeeping and not about Android delivering
 * the callbacks — that half is the platform's own promise.
 *
 * Instrumented all the same: [Activity] only constructs on a device, and this project runs no
 * Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class ActivityTrackerTest {

    private val tracker = ActivityTracker()

    private lateinit var first: Activity
    private lateinit var second: Activity

    /** [Activity]'s constructor makes a `Handler`, so even a bare instance needs the main thread. */
    @Before
    fun createActivitiesOnTheMainThread() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            first = Activity()
            second = Activity()
        }
    }

    @Test
    fun nothingResumedYet_hasNoForegroundActivity() {
        assertNull(tracker.current())
    }

    @Test
    fun theLastResumedActivity_isTheForegroundOne() {
        tracker.onActivityResumed(first)
        tracker.onActivityResumed(second)

        assertSame(second, tracker.current())
    }

    @Test
    fun pausingTheForegroundActivity_clearsIt() {
        tracker.onActivityResumed(first)
        tracker.onActivityPaused(first)

        assertNull(tracker.current())
    }

    /**
     * A pause can arrive out of order — split-screen focus changes, or a backgrounded activity
     * finally reporting in after the next one has resumed. The `===` guard is what keeps that from
     * blanking the activity that is genuinely in front, which would read as "no foreground
     * activity" and silently push every sign-in down the SDK fallback.
     */
    @Test
    fun pausingSomeOtherActivity_leavesTheForegroundAlone() {
        tracker.onActivityResumed(second)
        tracker.onActivityPaused(first)

        assertSame(second, tracker.current())
    }

    /**
     * The mark-then-wait shape `awaitReturn` uses: note the counter, leave, and wait for a value
     * *greater* than the note — which cannot miss, even when the resume lands before the wait
     * starts collecting.
     */
    @Test
    fun aMarkedWaiter_seesAResumeThatBeatItToTheFlow() = runBlocking {
        val mark = tracker.resumes.value
        tracker.onActivityResumed(first)

        val seen = withTimeout(WAIT_MS) { tracker.resumes.first { it > mark } }

        assertEquals(mark + 1, seen)
    }

    @Test
    fun aCreation_reachesAWaitingCollectorByClassName() = runBlocking {
        val name = async(start = CoroutineStart.UNDISPATCHED) { tracker.created.first() }

        tracker.onActivityCreated(first, null)

        assertEquals(Activity::class.java.name, withTimeout(WAIT_MS) { name.await() })
    }

    /**
     * No replay: a collector that subscribes *after* the creation never hears about it. That is
     * the contract `awaitReturn` is written against — its watcher subscribes before the user can
     * possibly come back — and this pins that a future caller cannot get away with subscribing
     * late and expecting history.
     */
    @Test
    fun aCreationWithNobodyWatching_isNotReplayedLater() = runBlocking {
        tracker.onActivityCreated(first, null)

        assertNull(withTimeoutOrNull(SILENCE_MS) { tracker.created.first() })
    }
}
