package com.gmail.volkovskiyda.jellyshelf.data.worker

import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldRetrySync], the rule that decides whether a failed sync goes back to WorkManager.
 *
 * Worth pinning because its failure is invisible: a sync that retries too eagerly shows nothing on
 * screen — it runs in a process nobody is looking at and costs only battery and radio time.
 */
class SyncRetryPolicyTest {

    private val transient = SyncResult.Error("Failed to load library: timeout", retryable = true)
    private val permanent = SyncResult.Error("Failed to load library: 401", retryable = false)

    @Test
    fun unattendedTransientFailure_isRetried() {
        assertTrue(shouldRetrySync(transient, manual = false, runAttemptCount = 0))
    }

    /**
     * The unreachable-server case: every attempt fails the same way, and without a bound the
     * periodic sync kept running on backoff instead of waiting for its next period.
     */
    @Test
    fun unattendedTransientFailure_givesUpAfterTheRetryBudget() {
        val runs = generateSequence(0) { it + 1 }
            .takeWhile { attempt -> shouldRetrySync(transient, manual = false, runAttemptCount = attempt) }
            .count() + 1

        assertEquals(MAX_SYNC_RETRIES + 1, runs, "one first run plus the retry budget")
    }

    @Test
    fun manualFailure_isNeverRetried() {
        assertFalse(shouldRetrySync(transient, manual = true, runAttemptCount = 0))
    }

    @Test
    fun permanentFailure_isNeverRetried() {
        assertFalse(shouldRetrySync(permanent, manual = false, runAttemptCount = 0))
    }
}
