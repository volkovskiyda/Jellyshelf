package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.ui.FakeUpdateFlags
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which sign-in path [AppDistributionSource] takes, and what it concludes from each.
 *
 * The launcher itself cannot be exercised off a device — it needs a foreground `Activity` and a
 * browser — but the *decision* around it is the whole risk of bypassing the SDK, and that is plain
 * logic. `TesterSignIn` exists as an interface so this can be tested at all.
 *
 * The SDK path is unreachable on the JVM (the Firebase singleton needs an Android runtime), so a
 * test that expects the fallback asserts it by the exception the stub produces on the way there,
 * not by a call count.
 */
class TesterSignInFallbackTest {

    private class RecordingSignIn(
        private val canStart: Boolean,
        private val signsIn: Boolean = true,
    ) : TesterSignIn {
        var starts = 0
        var returns = 0

        override suspend fun start(): Boolean {
            starts++
            return canStart
        }

        override suspend fun awaitReturn() {
            returns++
        }

        /** Stands in for the SDK's storage, which the redirect would have written by now. */
        fun signedIn() = canStart && signsIn && returns > 0
    }

    private fun source(
        launcher: RecordingSignIn,
        flags: FakeUpdateFlags = FakeUpdateFlags(),
    ) = object : AppDistributionSource(launcher, flags) {
        override fun isTesterSignedIn() = launcher.signedIn()
    }

    /** The happy path: our tab opened, the redirect landed, the SDK recorded it. */
    @Test
    fun aLauncherThatRuns_isTheOnlyPathTaken() = runTest {
        val launcher = RecordingSignIn(canStart = true)

        source(launcher).signInTester()

        assertEquals(1, launcher.starts)
        assertEquals(1, launcher.returns)
    }

    /**
     * The fallback that bounds the risk of bypassing the SDK. A launcher that cannot run — no
     * foreground activity, no browser, no installation id — must not be reported as a failed
     * sign-in; the SDK's own flow gets its turn instead.
     *
     * On the JVM that call reaches the API-only stub and throws, which is exactly the evidence
     * wanted: control got there rather than stopping at our launcher.
     */
    @Test
    fun aLauncherThatCannotRun_handsOverToTheSdk() = runTest {
        val launcher = RecordingSignIn(canStart = false)

        assertFailsWith<Throwable> { source(launcher).signInTester() }

        assertEquals(1, launcher.starts)
        assertEquals(0, launcher.returns, "Nothing to wait for — the tab was never opened")
    }

    /**
     * Coming back without being signed in is a cancellation, not a breakage.
     *
     * Deliberately *not* a second fallback attempt: we cannot tell "the user backed out" from "the
     * sign-in URL has changed shape", and throwing a second browser window at someone who just
     * chose to leave is the worse of the two mistakes.
     */
    @Test
    fun returningWithoutSigningIn_readsAsCancelled() = runTest {
        val launcher = RecordingSignIn(canStart = true, signsIn = false)

        val failure = assertFailsWith<UpdateCheckFailure> { source(launcher).signInTester() }

        assertEquals(
            com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError.SignInCancelled,
            failure.reason,
        )
        assertEquals(1, launcher.starts, "One tab, not two")
    }

    // --- The kill switch ---

    /**
     * The whole point of the switch: it must work when the launcher is the broken thing.
     *
     * So it is checked *before* start() is called, not after — a launcher that hangs, crashes or
     * reports success while opening nothing would defeat a switch consulted any later.
     */
    @Test
    fun theKillSwitch_neverAsksTheLauncherAtAll() = runTest {
        val launcher = RecordingSignIn(canStart = true)
        val flags = FakeUpdateFlags(legacySignIn = true)

        assertFailsWith<Throwable> { source(launcher, flags).signInTester() }

        assertEquals(0, launcher.starts, "The switch is on; the launcher must not run")
        assertEquals(0, launcher.returns)
    }

    /** Off is the shipped behaviour, and the default — an absent parameter must change nothing. */
    @Test
    fun theKillSwitchOff_leavesTheLauncherInCharge() = runTest {
        val launcher = RecordingSignIn(canStart = true)

        source(launcher, FakeUpdateFlags(legacySignIn = false)).signInTester()

        assertEquals(1, launcher.starts)
    }

    /** The sign-in is only ever concluded after the user is actually back. */
    @Test
    fun theResultIsReadAfterTheReturn_notBefore() = runTest {
        val order = mutableListOf<String>()
        val launcher = object : TesterSignIn {
            override suspend fun start(): Boolean {
                order += "start"
                return true
            }

            override suspend fun awaitReturn() {
                order += "return"
            }
        }
        val source = object : AppDistributionSource(launcher, FakeUpdateFlags()) {
            override fun isTesterSignedIn(): Boolean {
                order += "read"
                return true
            }
        }

        source.signInTester()

        assertEquals(listOf("start", "return", "read"), order)
        assertTrue(order.indexOf("read") > order.indexOf("return"))
        assertFalse(order.first() == "read")
    }
}
