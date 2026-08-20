package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.UpdateCheckSchedule

/**
 * The daily check's schedule, recorded rather than enqueued.
 *
 * Every [UpdateChecker][com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker] under test needs
 * one, and none of them wants a real WorkManager: the tests are about the checker's policy, and an
 * enqueue would outlive the test that made it.
 */
class RecordingUpdateCheckSchedule : UpdateCheckSchedule {
    var scheduled = 0
        private set
    var cancelled = 0
        private set

    override fun schedule() {
        scheduled++
    }

    override fun cancel() {
        cancelled++
    }
}
