package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GitHubReleaseSource] against Ktor's MockEngine — no network, no device. The payloads below are
 * shaped like the real `releases/latest` response for `v1.0`, whose assets really are
 * `jellyshelf-1.0.165.apk` + `mapping-1.0.165.txt`.
 *
 * The version identity under test is the one `release.yml` writes into the asset *name*, so these
 * tests are what keeps the app's parse and CI's naming from drifting apart.
 */
class GitHubReleaseSourceTest {

    private fun source(responseBody: String): GitHubReleaseSource {
        val engine = MockEngine {
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        // The real client config: expectSuccess so a non-2xx throws, and the shared lenient Json
        // (ignoreUnknownKeys) the production DTO relies on — not a copy of either.
        val client = HttpClient(engine) { expectSuccess = true }
        return GitHubReleaseSource(client, TestDispatcherProvider(), provideJson())
    }

    private fun release(assets: String, tag: String = "v1.0", body: String = "") = """
        {
          "url": "https://api.github.com/repos/volkovskiyda/Jellyshelf/releases/1",
          "tag_name": "$tag",
          "name": "$tag",
          "draft": false,
          "prerelease": false,
          "body": "$body",
          "assets": [$assets]
        }
    """.trimIndent()

    private fun asset(name: String) = """
        {
          "name": "$name",
          "content_type": "application/vnd.android.package-archive",
          "browser_download_url":
            "https://github.com/volkovskiyda/Jellyshelf/releases/download/v1.0/$name"
        }
    """.trimIndent()

    @Test
    fun theAssetNameCarriesTheVersionCode() = runTest {
        val notes = "**Full Changelog**: https://github.com/volkovskiyda/Jellyshelf/commits/v1.0"
        val info = source(
            release(
                assets = "${asset("jellyshelf-1.0.165.apk")}, ${asset("mapping-1.0.165.txt")}",
                body = notes,
            ),
        ).latestRelease()

        assertEquals(165, info?.versionCode)
        assertEquals("1.0", info?.versionName)
        assertEquals(notes, info?.releaseNotes)
        assertEquals(
            "https://github.com/volkovskiyda/Jellyshelf/releases/download/v1.0/" +
                "jellyshelf-1.0.165.apk",
            info?.downloadUrl,
        )
        assertEquals(UpdateSource.GITHUB, info?.source)
    }

    /** A notes-only release, or one whose APK upload failed: nothing to install, so no update. */
    @Test
    fun aReleaseWithNoApk_offersNothing() = runTest {
        val info = source(release(assets = asset("mapping-1.0.165.txt"))).latestRelease()

        assertNull(info)
    }

    /** A hand-uploaded APK that skipped `release.yml`'s naming carries no version code to read. */
    @Test
    fun anApkNamedSomeOtherWay_offersNothing() = runTest {
        val info = source(release(assets = asset("jellyshelf-release.apk"))).latestRelease()

        assertNull(info)
    }

    @Test
    fun twoApks_takeTheHigherVersionCode() = runTest {
        val info = source(
            release(
                assets = "${asset("jellyshelf-1.0.165.apk")}, ${asset("jellyshelf-1.0.170.apk")}",
            ),
        ).latestRelease()

        assertEquals(170, info?.versionCode)
        assertEquals(
            "https://github.com/volkovskiyda/Jellyshelf/releases/download/v1.0/" +
                "jellyshelf-1.0.170.apk",
            info?.downloadUrl,
        )
    }

    /**
     * The real payload carries dozens of fields (author, reactions, per-asset uploader…) that the
     * four-field DTO does not model. `ignoreUnknownKeys` is what makes that safe, so a change to
     * the shared Json config that dropped it would fail here rather than in the field.
     */
    @Test
    fun fieldsTheDtoDoesNotModel_doNotBreakTheDecode() = runTest {
        val payload = """
            {
              "id": 1,
              "author": { "login": "volkovskiyda", "id": 12345 },
              "tag_name": "v1.0",
              "reactions": { "url": "https://api.github.com/x", "total_count": 0 },
              "assets": [
                {
                  "id": 497700277,
                  "uploader": { "login": "github-actions[bot]" },
                  "name": "jellyshelf-1.0.165.apk",
                  "digest": "sha256:d0a142f21c18fa9a74217a2cae20a09bfb64f3a412814d07bd646508e2f4dde6",
                  "browser_download_url":
                    "https://github.com/volkovskiyda/Jellyshelf/releases/download/v1.0/jellyshelf-1.0.165.apk"
                }
              ]
            }
        """.trimIndent()

        val info = source(payload).latestRelease()

        assertEquals(165, info?.versionCode)
        // `body` is absent entirely here, not just empty — an update with no notes is still an
        // update, and the dialog renders no body rather than an empty box.
        assertEquals("", info?.releaseNotes)
    }
}
