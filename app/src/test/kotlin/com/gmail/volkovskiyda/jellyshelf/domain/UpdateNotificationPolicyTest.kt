package com.gmail.volkovskiyda.jellyshelf.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OFFERED = 200
private const val NEVER_NOTIFIED = 0

/**
 * When a daily background check is allowed to interrupt.
 *
 * Every "no" here is a way of not being annoying, and each is easy to lose by accident — the
 * feature would still look like it worked, while announcing the same release every morning, or
 * talking over the dialog that is already on screen saying the same thing.
 */
class UpdateNotificationPolicyTest {

    private fun decide(
        offered: Int? = OFFERED,
        lastNotified: Int = NEVER_NOTIFIED,
        foreground: Boolean = false,
        granted: Boolean = true,
    ) = shouldNotifyAboutUpdate(offered, lastNotified, foreground, granted)

    @Test
    fun aNewVersionFoundInTheBackgroundIsAnnounced() {
        assertTrue(decide())
    }

    @Test
    fun findingNothingAnnouncesNothing() {
        assertFalse(decide(offered = null))
    }

    /** The in-app dialog is already the surface, and it is the one the user is looking at. */
    @Test
    fun anOpenAppIsLeftToItsOwnDialog() {
        assertFalse(decide(foreground = true))
    }

    /**
     * Posting without the permission is a silent no-op, not an error — and stamping the version as
     * notified afterwards would mean the *next* check, with the permission granted, says nothing.
     */
    @Test
    fun withoutThePermissionNothingIsPosted() {
        assertFalse(decide(granted = false))
    }

    /** The check runs daily and the offer persists; without this the same release nags forever. */
    @Test
    fun theSameVersionIsAnnouncedOnlyOnce() {
        assertFalse(decide(lastNotified = OFFERED))
    }

    @Test
    fun aNewerVersionIsAnnouncedAgain() {
        assertTrue(decide(offered = OFFERED + 1, lastNotified = OFFERED))
    }
}
