package com.gmail.volkovskiyda.jellyshelf.data.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.ApkInstallFailure
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

private val APK_BYTES = ByteArray(200_000) { (it % 251).toByte() }

/**
 * The download half of the in-app install, against a real stream and a real file.
 *
 * The verification rule has unit tests; this is the part they cannot reach — that the bytes written
 * to disk are the bytes that were hashed, that a mismatch leaves nothing behind, and that a body
 * larger than one chunk survives the read at all. Getting any of those wrong produces an install
 * that works on the happy path and verifies nothing.
 *
 * Committing the session is deliberately not exercised: it would install this APK over the test.
 */
@RunWith(AndroidJUnit4::class)
class ApkInstallerInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun installer(body: ByteArray = APK_BYTES): ApkInstaller {
        val engine = MockEngine {
            respond(
                content = body,
                headers = headersOf(HttpHeaders.ContentLength, body.size.toString()),
            )
        }
        return ApkInstaller(
            context = context,
            httpClient = HttpClient(engine) { install(HttpTimeout) },
            dispatchers = TestDispatcherProvider(),
        )
    }

    private fun info(sha256: String?) = UpdateInfo(
        versionCode = 200,
        versionName = "1.0.200",
        releaseNotes = "",
        downloadUrl = "https://example.invalid/jellyshelf-1.0.200.apk",
        sha256 = sha256,
        source = UpdateSource.GITHUB,
    )

    private fun updatesDir() = File(context.cacheDir, "updates")

    @Before
    fun clean() = ApkInstaller.sweep(context)

    @After
    fun cleanAfter() = ApkInstaller.sweep(context)

    @Test
    fun aMatchingDownloadIsKeptExactly() = runBlocking {
        val expected = sha256(APK_BYTES)

        val file = installer().downloadVerified(info(expected), expected) {}

        assertTrue("the APK was not kept", file.exists())
        assertEquals(APK_BYTES.size.toLong(), file.length())
        // Hashed while streaming, so this is what proves the bytes on disk are the bytes verified.
        assertEquals(expected, sha256(file.readBytes()))
    }

    /**
     * The failure this whole class exists for. A mismatch must stop the install *and* leave nothing
     * on disk — a rejected APK that survives is one a later bug can still reach.
     */
    @Test
    fun aMismatchedDownloadFailsAndIsDeleted() {
        val wrong = "b".repeat(64)

        val thrown = runCatching {
            runBlocking { installer().downloadVerified(info(wrong), wrong) {} }
        }.exceptionOrNull()

        assertTrue("expected an ApkInstallFailure, got $thrown", thrown is ApkInstallFailure)
        assertEquals(UpdateCheckError.VerificationFailed, (thrown as ApkInstallFailure).reason)
        assertTrue(
            "a rejected APK was left on disk",
            updatesDir().listFiles().orEmpty().isEmpty(),
        )
    }

    /** No digest is a mismatch, not a pass — the case that would silently install anything. */
    @Test
    fun aDownloadWithNoExpectedDigestIsRejected() {
        val thrown = runCatching {
            runBlocking { installer().downloadVerified(info(null), null) {} }
        }.exceptionOrNull()

        assertEquals(
            UpdateCheckError.VerificationFailed,
            (thrown as ApkInstallFailure).reason,
        )
        assertTrue(updatesDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun progressClimbsToTheFullSize() = runBlocking {
        val expected = sha256(APK_BYTES)
        val seen = mutableListOf<InstallState.Running>()

        installer().downloadVerified(info(expected), expected) { seen += it }

        assertTrue("no progress was reported", seen.isNotEmpty())
        assertTrue(seen.all { it.stage == InstallStage.DOWNLOADING })
        assertEquals(APK_BYTES.size.toLong(), seen.last().bytesDownloaded)
        assertEquals(APK_BYTES.size.toLong(), seen.last().totalBytes)
        assertEquals(1f, seen.last().fraction)
    }

    /** Start-up cleanup: a part-file from a killed process must not survive to confuse anything. */
    @Test
    fun sweepRemovesLeftovers() {
        updatesDir().mkdirs()
        val leftover = File(updatesDir(), "jellyshelf-199.apk").apply { writeBytes(byteArrayOf(1)) }

        ApkInstaller.sweep(context)

        assertFalse(leftover.exists())
    }
}
