package com.gmail.volkovskiyda.jellyshelf.domain

/**
 * The daily background update check, as much of it as [UpdateChecker] needs to know: it can be on
 * or off, and which one follows from the channel the user picked.
 *
 * An interface in the domain because the decision belongs with the rest of the update policy, while
 * the thing being decided is a WorkManager enqueue two layers down —
 * [UpdateCheckScheduler][com.gmail.volkovskiyda.jellyshelf.data.worker.UpdateCheckScheduler] is
 * bound to this, exactly as the repositories are bound to theirs.
 */
interface UpdateCheckSchedule {
    fun schedule()
    fun cancel()
}
