package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The repo `git remote -v` points at — one place, so the URL is never assembled twice. */
private const val REPO = "volkovskiyda/Jellyshelf"

/**
 * `releases/latest` deliberately, not `releases`: it excludes drafts and pre-releases, so a
 * pre-release cut for testing does not prompt every user who watches this channel. Do not "fix"
 * this to the plural endpoint.
 */
private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

/**
 * The published APK's name, as `release.yml` writes it:
 *
 * ```
 * V="${GITHUB_REF_NAME#v}.$(git rev-list --count HEAD)"
 * cp app/build/outputs/apk/release/app-release.apk "jellyshelf-$V.apk"
 * ```
 *
 * so the trailing integer is exactly the `versionCode` the APK reports — the same integer the app
 * compares against `BuildConfig.VERSION_CODE`. That is why no separate `version.json` is needed:
 * the asset name already carries the identity. Anchored, and compiled once as a top-level `val`.
 */
private val APK_ASSET = Regex("""^jellyshelf-.*\.(\d+)\.apk$""")

/**
 * The latest published GitHub release, for the update check. Plain HTTP against the public API with
 * the shared [HttpClient] — no Jellyfin client, no credential — the same shape as [IndexSource].
 *
 * **Unauthenticated on purpose.** GitHub allows 60 requests/hour per IP unauthenticated, and the
 * check runs at most once a day (plus whatever "Check now" the user taps), which cannot approach
 * that. Adding a token would mean shipping one in the APK for no benefit — don't.
 */
class GitHubReleaseSource(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    /**
     * The latest release, or null when it publishes no APK asset this app recognizes — a
     * notes-only or malformed release, which is not something to offer as an update.
     *
     * Throws on HTTP failure: the shared client has `expectSuccess = true`, so a non-2xx surfaces
     * as a `ResponseException`. Deciding what a failure *means* to the user belongs to the checker,
     * not here — the same contract [IndexSource.fetchIndex] documents.
     */
    suspend fun latestRelease(): UpdateInfo? = withContext(dispatchers.io) {
        val response = httpClient.get(LATEST_RELEASE_URL) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        // Decoded by hand rather than through ContentNegotiation so the DTO travels with the shared
        // lenient Json (ignoreUnknownKeys), which is what makes a four-field DTO safe against
        // GitHub's very large release payload.
        val release = json.decodeFromString(GitHubRelease.serializer(), response.bodyAsText())

        // A release that ever publishes two APKs takes the highest code — the newest build wins
        // rather than whichever the API happened to list first.
        release.assets
            .mapNotNull { asset ->
                APK_ASSET.find(asset.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let { versionCode -> versionCode to asset }
            }
            .maxByOrNull { (versionCode, _) -> versionCode }
            ?.let { (versionCode, asset) ->
                UpdateInfo(
                    versionCode = versionCode,
                    // `-PreleaseVersion` is fed the tag without its leading v (release.yml:40), so
                    // stripping it here reproduces the versionName the APK actually reports.
                    versionName = release.tagName.removePrefix("v"),
                    releaseNotes = release.body.orEmpty().trim(),
                    downloadUrl = asset.browserDownloadUrl,
                    source = UpdateSource.GITHUB,
                )
            }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)
