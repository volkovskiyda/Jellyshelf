package com.gmail.volkovskiyda.jellyshelf.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

/**
 * [isPermanentFailure] decides whether the sync worker retries, so getting it wrong costs either a
 * failure that never surfaces (retrying forever on a revoked key) or one that surfaces too early.
 *
 * The exceptions are produced by driving a real Ktor client with `expectSuccess = true` against a
 * MockEngine rather than by constructing `ResponseException` by hand — what matters is the shape
 * Ktor actually throws in production.
 */
class PermanentFailureTest {

    /** The exception a real `expectSuccess = true` client throws for [status]. */
    private suspend fun failureFor(status: HttpStatusCode): Throwable {
        val client = HttpClient(MockEngine { respond(content = "", status = status) }) {
            expectSuccess = true
        }
        return runCatching { client.get("http://server:8096/Items") }.exceptionOrNull()!!
    }

    @Test
    fun `client errors that name a bad request are permanent`() = runTest {
        // Revoked/incorrect api key, and a user or folder that no longer exists.
        assertTrue(isPermanentFailure(failureFor(HttpStatusCode.Unauthorized)))
        assertTrue(isPermanentFailure(failureFor(HttpStatusCode.Forbidden)))
        assertTrue(isPermanentFailure(failureFor(HttpStatusCode.NotFound)))
        assertTrue(isPermanentFailure(failureFor(HttpStatusCode.BadRequest)))
    }

    @Test
    fun `the two explicitly transient client errors are retryable`() = runTest {
        assertFalse(isPermanentFailure(failureFor(HttpStatusCode.RequestTimeout)))
        assertFalse(isPermanentFailure(failureFor(HttpStatusCode.TooManyRequests)))
    }

    @Test
    fun `server errors are retryable`() = runTest {
        assertFalse(isPermanentFailure(failureFor(HttpStatusCode.InternalServerError)))
        assertFalse(isPermanentFailure(failureFor(HttpStatusCode.BadGateway)))
        assertFalse(isPermanentFailure(failureFor(HttpStatusCode.ServiceUnavailable)))
    }

    @Test
    fun `a plain network error is retryable`() {
        assertFalse(isPermanentFailure(IOException("Connection refused")))
    }

    /**
     * A cleartext block never reaches the server, so it arrives as an [UnknownServiceException]
     * rather than a `ResponseException` — the case that used to be classified transient and let
     * the periodic worker retry an `http://` URL every 3 hours forever.
     */
    @Test
    fun `a cleartext block is permanent`() {
        val blocked = UnknownServiceException(
            "CLEARTEXT communication to 192.168.1.10 not permitted by network security policy",
        )
        assertTrue(isPermanentFailure(blocked))
        assertTrue(isPermanentFailure(IOException("Request failed", blocked)))
    }
}
