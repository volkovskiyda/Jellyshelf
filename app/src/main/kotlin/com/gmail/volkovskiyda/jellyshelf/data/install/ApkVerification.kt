package com.gmail.volkovskiyda.jellyshelf.data.install

/**
 * Whether a downloaded APK is the one the release published.
 *
 * A plain comparison, extracted so the one rule that makes an in-app install defensible can be
 * read and tested on its own — the download around it is all streams and files, and none of the
 * judgement lives there.
 *
 * Case-insensitive because hex has two spellings and neither is more correct; whitespace-trimmed
 * because a digest that arrives with a stray newline is still the same digest. Everything else is
 * a mismatch, including an *absent* expectation: a caller that reached this point without a digest
 * has already lost the argument for installing anything, and defaulting to "fine" is exactly the
 * failure mode this function exists to prevent.
 */
fun apkMatchesDigest(expectedSha256: String?, actualSha256: String): Boolean {
    val expected = expectedSha256?.trim()?.lowercase()
    if (expected.isNullOrEmpty()) return false
    return expected == actualSha256.trim().lowercase()
}

/** Lower-case hex, the spelling GitHub reports digests in. */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
