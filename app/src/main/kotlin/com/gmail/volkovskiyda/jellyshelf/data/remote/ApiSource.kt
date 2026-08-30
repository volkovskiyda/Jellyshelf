package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The metadata API on the bot server that downloaded the library's videos. Serves the same
 * [IndexEntry] document format as the hosted index, but live: a video appears here the moment it
 * lands in the library, while the index catches up on its own schedule.
 *
 * Like [IndexSource], this is not a Jellyfin endpoint — it talks to a user-configured base URL
 * with its own bearer token, so it uses the base client directly rather than the Jellyfin one.
 */
class ApiSource(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    /**
     * Every exported video the API knows, as index entries. Throws on HTTP failure (401 included)
     * for the same reason [IndexSource.fetchIndex] does: a failed fetch must stay distinguishable
     * from an API that genuinely has no entries, or it would degrade API-sourced rows.
     */
    suspend fun fetchVideos(apiUrl: String, token: String): List<IndexEntry> =
        withContext(dispatchers.io) {
            // Only videos that were exported into the media library: the others can never join a
            // Jellyfin item, so shipping them would be pure payload.
            val url = "${apiUrl.trim().trimEnd('/')}/videos?exported=true"
            val response = httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            decodeIndexEntries(json, response.bodyAsChannel().toInputStream())
        }
}
