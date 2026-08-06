package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.data.remote.toUpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.data.remote.toUpdateCheckFailure
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * The App Distribution failure mapping, which is the whole of that source that can be tested off a
 * device: the SDK's singleton cannot be faked usefully on the JVM, so nothing here instantiates it.
 * That is why the mapping is a function over the enum and nothing else.
 *
 * The live path — a real tester, a real release, a real install — is verified by hand once the API
 * key allows `firebaseapptesters.googleapis.com`; `docs/RELEASING.md` carries that checklist.
 */
class UpdateCheckErrorTest {

    /**
     * The guard that matters most: a future SDK bump adding a `Status` constant must fail the
     * build at the mapping's exhaustive `when`, not degrade a brand-new failure mode to "unknown".
     * This test proves the mapping covers today's set; the missing `else` covers tomorrow's.
     */
    @Test
    fun everyStatus_isMapped() {
        assertEquals(11, Status.values().size)

        Status.values()
            .filter { it != Status.UPDATE_NOT_AVAILABLE }
            .forEach { status ->
                assertNotNull("$status has no mapped reason", status.toUpdateCheckError())
            }
    }

    /** No two statuses share a case — otherwise the "real reason" promise is only half kept. */
    @Test
    fun noTwoStatusesShareAReason() {
        val mapped = Status.values().mapNotNull { it.toUpdateCheckError() }

        assertEquals(mapped.size, mapped.toSet().size)
    }

    /** "You are already current" is a result, not a failure, and must never render as an error. */
    @Test
    fun beingUpToDate_isNotAnError() {
        assertNull(Status.UPDATE_NOT_AVAILABLE.toUpdateCheckError())
    }

    /**
     * The single most likely failure until the API key allows the App Testers API — and the one
     * that must not read as "no update available", since nothing was ever asked.
     */
    @Test
    fun aBlockedApiKey_saysSo() {
        assertEquals(UpdateCheckError.ApiDisabled, Status.API_DISABLED.toUpdateCheckError())
    }

    /** What the API-only stub returns on every call: debug and the profiling variants. */
    @Test
    fun theStub_saysItIsNotSupported() {
        assertEquals(UpdateCheckError.NotSupported, Status.NOT_IMPLEMENTED.toUpdateCheckError())
    }

    /** Backing out of the sign-in Custom Tab is an answer, and gets its own case to be worded as one. */
    @Test
    fun backingOutOfSignIn_isItsOwnCase() {
        assertEquals(
            UpdateCheckError.SignInCancelled,
            Status.AUTHENTICATION_CANCELED.toUpdateCheckError(),
        )
        assertEquals(
            UpdateCheckError.NotATester,
            Status.AUTHENTICATION_FAILURE.toUpdateCheckError(),
        )
    }

    /**
     * A transport failure carries no `Status` at all, and must still arrive with a reason rather
     * than a null one — a null reason means "up to date", which this very much is not.
     *
     * Only the not-a-Firebase-failure direction is exercised here: constructing a real
     * `FirebaseAppDistributionException` is impossible on the JVM, since `FirebaseException`'s
     * constructor runs `Preconditions.checkNotEmpty` through `android.text.TextUtils`. That is the
     * same reason nothing here instantiates the SDK singleton, and why the mapping is a function
     * over the enum in the first place.
     */
    @Test
    fun aFailureThatIsNotAFirebaseOne_readsAsUnknown() {
        val failure = IOException("socket closed").toUpdateCheckFailure()

        assertEquals(UpdateCheckError.Unknown, failure.reason)
    }
}
