package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

class NetworkErrorsTest {

    /** Verbatim text of the exception OkHttp raises when the security policy rejects plain HTTP. */
    private fun cleartext() = UnknownServiceException(
        "CLEARTEXT communication to 192.168.1.10 not permitted by network security policy",
    )

    @Test
    fun `detects the cleartext block when thrown directly`() {
        assertTrue(isCleartextBlocked(cleartext()))
    }

    @Test
    fun `detects the cleartext block through a wrapping cause chain`() {
        val wrapped = IOException("Request failed", IllegalStateException("engine", cleartext()))
        assertTrue(isCleartextBlocked(wrapped))
    }

    @Test
    fun `leaves unrelated network failures alone`() {
        assertFalse(isCleartextBlocked(IOException("Connection refused")))
    }

    @Test
    fun `does not match an UnknownServiceException raised for another reason`() {
        assertFalse(isCleartextBlocked(UnknownServiceException("no such service")))
    }

    @Test
    fun `terminates on a self-referential cause chain`() {
        // Some libraries initCause() a throwable to itself; the walk must not spin.
        val looping = object : IOException("looping") {
            override val cause: Throwable get() = this
        }
        assertFalse(isCleartextBlocked(looping))
    }
}
