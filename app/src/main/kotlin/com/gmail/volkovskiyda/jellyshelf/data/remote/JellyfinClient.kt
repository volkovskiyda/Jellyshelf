package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.mediaBrowserAuthHeader
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders

/**
 * Builds a [JellyfinApi] against a runtime-configured server URL + credential.
 *
 * Turning that credential into a request header is this class's job and nowhere else's: since
 * Jellyfin 12 it travels in a `MediaBrowser` `Authorization` header — see
 * [mediaBrowserAuthHeader] for what the server dropped — and a header string and a raw token are
 * both `String`, so a seam that passed the finished header around would be one a caller could get
 * silently backwards.
 */
class JellyfinClient(
    private val baseClient: HttpClient,
    private val settings: SettingsRepository,
    private val deviceInfo: DeviceInfo,
) {
    /**
     * Suspends to read (and on first use generate) the install's device id, which is one of the
     * header's identity fields. [credential] is the user access token or the advanced admin API
     * key — or blank for the login, the one call made before either exists.
     */
    suspend fun create(serverUrl: String, credential: String): JellyfinApi {
        val authorization = mediaBrowserAuthHeader(deviceInfo, settings.deviceId(), credential)
        // .config { } returns a client that shares the base engine (connection pool, timeouts,
        // ContentNegotiation, Logging), so per-server clients are cheap — the Ktor analogue of
        // okhttp.newBuilder(). JellyfinDataSource still caches the result by "url|key".
        val client = baseClient.config {
            defaultRequest {
                url(normalizeBaseUrl(serverUrl))
                // Sent on every request, the login included: there the header carries the client
                // identity with no Token field, which is exactly what AuthenticateByName wants.
                header(HttpHeaders.Authorization, authorization)
                accept(ContentType.Application.Json)
            }
        }
        return JellyfinApi(client)
    }

    // Ktor resolves relative request paths against a base URL that ends in '/' (browser-style
    // resolution), and none of the endpoint paths start with '/'.
    private fun normalizeBaseUrl(url: String): String =
        "${url.trim().removeSuffix("/")}/"
}
