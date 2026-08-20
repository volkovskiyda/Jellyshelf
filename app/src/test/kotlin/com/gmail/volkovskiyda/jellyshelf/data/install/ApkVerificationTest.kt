package com.gmail.volkovskiyda.jellyshelf.data.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/**
 * The check that stands between "bytes from the internet" and "handed to the package installer".
 *
 * Every case here is a way of accidentally installing something unverified, and all of them are
 * silent: the install succeeds, the app updates, and nobody finds out that the digest was never
 * really compared.
 */
class ApkVerificationTest {

    @Test
    fun theSameDigestMatches() {
        assertTrue(apkMatchesDigest(DIGEST, DIGEST))
    }

    @Test
    fun aDifferentDigestDoesNot() {
        assertFalse(apkMatchesDigest(DIGEST, DIGEST.dropLast(1) + "0"))
    }

    /** Hex has two spellings; neither is more correct, and a case difference is not a mismatch. */
    @Test
    fun caseDoesNotMatter() {
        assertTrue(apkMatchesDigest(DIGEST.uppercase(), DIGEST))
        assertTrue(apkMatchesDigest(DIGEST, DIGEST.uppercase()))
    }

    @Test
    fun surroundingWhitespaceDoesNotMatter() {
        assertTrue(apkMatchesDigest("  $DIGEST\n", DIGEST))
    }

    /**
     * The one that matters most. "No expectation" must never read as "anything is fine" — that is
     * precisely the shape a verification bug takes, and it passes every happy-path test.
     */
    @Test
    fun noExpectedDigestNeverMatches() {
        assertFalse(apkMatchesDigest(null, DIGEST))
        assertFalse(apkMatchesDigest("", DIGEST))
        assertFalse(apkMatchesDigest("   ", DIGEST))
    }

    @Test
    fun hexIsLowerCaseAndZeroPadded() {
        assertEquals("000faf", byteArrayOf(0, 0x0f, 0xaf.toByte()).toHexString())
    }
}
