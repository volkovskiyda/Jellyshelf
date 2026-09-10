package com.gmail.volkovskiyda.jellyshelf.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.JourneyPermissionsRule
import com.gmail.volkovskiyda.jellyshelf.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VERSION_NAME = "1.0.200"
private const val POST_TIMEOUT_MS = 5_000L
private const val POLL_MS = 50L

/**
 * The app's only self-posted notification, against the real NotificationManager.
 *
 * Worth a device test rather than a unit one because every part that can be wrong here is
 * platform-side: whether the channel exists by the time the notification references it, whether
 * the builder produces something the system accepts, and whether the tap intent carries the extra
 * MainActivity reads. A notification that is silently never posted looks exactly like a background
 * check that found nothing.
 */
@RunWith(AndroidJUnit4::class)
class UpdateNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Posting is a no-op without it, which would make every assertion below vacuous. */
    @get:Rule
    val journeyPermissions = JourneyPermissionsRule()

    @After
    fun clearNotifications() {
        manager.cancelAll()
    }

    private fun updateNotifications() =
        manager.activeNotifications.filter { it.notification.channelId == "updates" }

    /**
     * Polled rather than read once: `notify` hands the notification to the system service and
     * returns, so `activeNotifications` catches up a moment later. Reading immediately turns that
     * moment into "nothing was posted", which is the one answer this class must not get wrong by
     * accident — it is also what a genuinely broken post looks like.
     */
    private fun awaitPosted(): StatusBarNotification {
        val deadline = SystemClock.uptimeMillis() + POST_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            updateNotifications().firstOrNull()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        error("no update notification was posted within ${POST_TIMEOUT_MS}ms")
    }

    @Test
    fun postingPutsTheUpdateInTheShade() {
        UpdateNotification.post(context, VERSION_NAME)

        val extras = awaitPosted().notification.extras
        assertEquals(
            context.getString(R.string.update_notification_title),
            extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.update_notification_text, VERSION_NAME),
            extras.getString(NotificationCompat.EXTRA_TEXT),
        )
    }

    /**
     * Low importance and no sound: an update is worth finding, never worth interrupting. This is
     * the whole difference between a helpful notification and one the user turns the channel off
     * to escape.
     */
    @Test
    fun theChannelIsQuiet() {
        UpdateNotification.post(context, VERSION_NAME)
        awaitPosted()

        val channel = manager.getNotificationChannel("updates")
        assertNotNull("the channel was never created", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    /** Tapping has to reach the app *and* say why, or it opens on no dialog at all. */
    @Test
    fun tappingCarriesTheShowUpdateExtra() {
        UpdateNotification.post(context, VERSION_NAME)

        val posted = awaitPosted().notification
        assertNotNull("no tap intent", posted.contentIntent)
        assertTrue("not auto-cancelling", posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    /** A second check replaces the first rather than stacking a pile of the same news. */
    @Test
    fun postingTwiceLeavesOneNotification() {
        UpdateNotification.post(context, VERSION_NAME)
        UpdateNotification.post(context, "1.0.201")
        awaitPosted()

        assertEquals(1, updateNotifications().size)
    }
}
