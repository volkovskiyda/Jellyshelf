package com.gmail.volkovskiyda.jellyshelf.data.remote

import android.content.Context
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
import java.io.InputStream

/** The bundled demo dataset, packaged into the APK — see [IndexSource.demoEntries]. */
private const val DEMO_LIBRARY_ASSET = "demo/library.json"

/** What a demo metadata fetch "extracts" — see [IndexSource.demoFetchedEntries]. */
private const val DEMO_FETCHED_ASSET = "demo/fetched.json"

/**
 * Every [IndexEntry] list the app reads: the aggregated yt-dlp metadata index fetched from wherever
 * the user hosts it ([fetchIndex]), and the bundled demo dataset ([demoEntries],
 * [demoFetchedEntries]). One document format, one decoder, two origins.
 *
 * Separate from [JellyfinDataSource][com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource]
 * on purpose: the index does not live on the Jellyfin server, so this talks plain HTTP with no
 * Jellyfin client, headers or credential — and the demo document never leaves the device at all.
 */
class IndexSource(
    private val context: Context,
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    /**
     * Throws on HTTP failure — callers must be able to tell a failed fetch from an index that is
     * genuinely empty, since the former must never degrade existing index-sourced metadata.
     */
    suspend fun fetchIndex(indexUrl: String): List<IndexEntry> = withContext(dispatchers.io) {
        // indexUrl is an arbitrary absolute URL (not a Jellyfin endpoint), so it uses the base
        // httpClient directly. With expectSuccess = true a non-2xx throws a ResponseException here
        // — still a throw, which is all this method's contract promises.
        decode(httpClient.get(indexUrl).bodyAsChannel().toInputStream())
    }

    /**
     * The bundled demo library — a real jellyshelf-index document, the same format
     * `scripts/build-library-index.sh` emits, so the asset doubles as a worked example of it.
     *
     * Throws if the asset is missing or malformed, which can only mean a broken build: unlike
     * [fetchIndex] there is no network in between to fail transiently, so there is nothing to
     * degrade gracefully to.
     */
    suspend fun demoEntries(): List<IndexEntry> = withContext(dispatchers.io) {
        decode(context.assets.open(DEMO_LIBRARY_ASSET))
    }

    /**
     * The metadata a demo "yt-dlp extraction" produces for the entries [demoEntries] deliberately
     * leaves bare — the unmatched files a real library always has a few of. Same format again, kept
     * out of the seed document precisely because those rows must *start* without it: filling them
     * in is what the Uncategorized filter's bulk fetch is there to demonstrate.
     *
     * Throws like [demoEntries], and for the same reason.
     */
    suspend fun demoFetchedEntries(): List<IndexEntry> = withContext(dispatchers.io) {
        decode(context.assets.open(DEMO_FETCHED_ASSET))
    }

    /**
     * Decoded straight off the stream rather than via a String: a 10k-video index runs to tens of
     * megabytes of JSON, and buffering the whole document before parsing it would hold a second
     * full copy for no gain.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun decode(stream: InputStream): List<IndexEntry> = stream.use {
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
