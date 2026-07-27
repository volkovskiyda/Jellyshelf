package com.gmail.volkovskiyda.jellyshelf.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType

/**
 * Builds a [JellyfinApi] against a runtime-configured server URL + API key.
 * The API key is injected as the X-Emby-Token header on every request.
 */
class JellyfinClient(private val baseClient: HttpClient) {
    fun create(serverUrl: String, credential: String): JellyfinApi {
        // .config { } returns a client that shares the base engine (connection pool, timeouts,
        // ContentNegotiation, Logging), so per-server clients are cheap — the Ktor analogue of
        // okhttp.newBuilder(). JellyfinDataSource still caches the result by "url|key".
        val client = baseClient.config {
            defaultRequest {
                url(normalizeBaseUrl(serverUrl))
                header("X-Emby-Token", credential)
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
