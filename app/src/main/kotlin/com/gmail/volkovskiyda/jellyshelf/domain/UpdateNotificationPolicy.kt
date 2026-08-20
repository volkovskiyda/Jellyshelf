package com.gmail.volkovskiyda.jellyshelf.domain

/**
 * Whether a background check that found an update should put a notification up.
 *
 * A plain decision over four facts, so the rules can be read and tested without a worker, a
 * notification manager or a device — the parts around it are all Android, and none of them is
 * where the judgement lives.
 *
 * @param offeredVersionCode the version the check is offering, or null when it found nothing.
 * @param lastNotifiedVersionCode the version already notified about, `0` when never.
 * @param appIsForeground whether an activity is resumed right now.
 * @param permissionGranted whether notifications may be posted at all.
 */
fun shouldNotifyAboutUpdate(
    offeredVersionCode: Int?,
    lastNotifiedVersionCode: Int,
    appIsForeground: Boolean,
    permissionGranted: Boolean,
): Boolean {
    // Nothing to say.
    if (offeredVersionCode == null) return false
    // The app is on screen, so the dialog is already the right surface — and it is a better one,
    // being where the user is looking. Two announcements of the same thing is noise.
    if (appIsForeground) return false
    // Posting without the permission is not an error, it is a silent no-op; treating it as a
    // notification would burn the once-per-version stamp on something nobody ever saw.
    if (!permissionGranted) return false
    // Once per version, not once per check: the check runs daily and the offer survives until it
    // is acted on, so without this the same release would be announced every morning. Deliberately
    // *not* tied to dismissal — swiping a notification away is not the 7-day snooze, which belongs
    // to the dialog's explicit dismiss.
    return offeredVersionCode != lastNotifiedVersionCode
}
