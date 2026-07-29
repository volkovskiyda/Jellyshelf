package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.IOException

/**
 * Fetches the aggregated yt-dlp metadata index ([IndexEntry] list). Separate from
 * [JellyfinDataSource][com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource] on
 * purpose: the index lives wherever the user hosts it — not on the Jellyfin server — so this talks
 * plain HTTP with no Jellyfin client, headers or credential.
 */
class IndexSource(
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    /**
     * Throws on HTTP failure — callers must be able to tell a failed fetch from an index that is
     * genuinely empty, since the former must never degrade existing index-sourced metadata.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchIndex(indexUrl: String): List<IndexEntry> = withContext(dispatchers.io) {
        // indexUrl is an arbitrary absolute URL (not a Jellyfin endpoint), so it uses the base
        // httpClient directly. With expectSuccess = true a non-2xx throws a ResponseException here
        // — still a throw, which is all this method's contract promises.
        val stream = httpClient.get(indexUrl).bodyAsChannel().toInputStream()
        // Decoded straight off the response stream rather than via bodyAsText(): a 10k-video
        // index runs to tens of megabytes of JSON, and buffering the whole document into a String
        // before parsing it would hold a second full copy for no gain.
        stream.use {
            try {
                json.decodeFromStream(ListSerializer(IndexEntry.serializer()), it)
            } catch (e: SerializationException) {
                // A legitimate index is always a JSON array (build-library-index.sh emits "[]" at
                // minimum), so a blank or non-JSON 200 — captive portal, file caught mid-rewrite —
                // must count as a failed fetch, or it would downgrade every index-sourced row.
                throw IOException("Index fetch returned no usable JSON array", e)
            }
        }
    }
}
