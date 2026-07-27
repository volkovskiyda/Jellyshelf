package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.CreatePlaylistBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinApi
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserItemDataBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import timber.log.Timber

/** Paging safety cap — far above any real library, purely an infinite-loop backstop. */
private const val MAX_PAGED_ITEMS = 1_000_000

/** Shared logcat tag for the external-player / playstate flow: `adb logcat -s Playback`. Kept in
 *  the data layer so it doesn't depend on the Android-heavy `util.Playback`. */
private const val PLAYBACK_TAG = "Playback"

/**
 * Low-level Jellyfin data source: every call returns raw DTOs and is used only by other data-layer
 * classes. The UI-facing subset lives behind the domain [com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository]
 * interface (see [DefaultJellyfinRepository]).
 */
class JellyfinDataSource(
    private val client: JellyfinClient,
    private val httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    // Cache the built API by "url|key" so we don't reconfigure the client each call. A single
    // volatile pair keeps the key and its API published atomically, so a concurrent settings change
    // can never pair one server's key with another server's client.
    private data class CachedApi(val key: String, val api: JellyfinApi)

    @Volatile
    private var cached: CachedApi? = null

    private fun api(serverUrl: String, apiKey: String): JellyfinApi {
        val key = "$serverUrl|$apiKey"
        cached?.let { if (it.key == key) return it.api }
        return client.create(serverUrl, apiKey).also { cached = CachedApi(key, it) }
    }

    suspend fun getUsers(serverUrl: String, apiKey: String): List<UserDto> =
        api(serverUrl, apiKey).getUsers()

    /** Top-level libraries/collections for the user. */
    suspend fun getViews(serverUrl: String, apiKey: String, userId: String): List<BaseItemDto> =
        api(serverUrl, apiKey).getViews(userId).items

    /**
     * Immediate child folders of [parentId]. A blank [parentId] returns the user's
     * top-level collections (views); anything deeper returns that folder's subfolders.
     */
    suspend fun getChildFolders(
        serverUrl: String,
        apiKey: String,
        userId: String,
        parentId: String?,
    ): List<BaseItemDto> {
        val api = api(serverUrl, apiKey)
        val pid = parentId?.takeIf { it.isNotBlank() }
        val items = if (pid == null) {
            api.getViews(userId).items
        } else {
            // Page through so a folder with more than one page of children isn't silently
            // truncated in the browser. A short page is the sole terminator — some servers
            // omit TotalRecordCount, and a full page with total=0 must not stop the loop.
            val all = mutableListOf<BaseItemDto>()
            val pageSize = 500
            var startIndex = 0
            while (true) {
                val page = api.getChildFolders(
                    userId = userId,
                    parentId = pid,
                    startIndex = startIndex,
                    limit = pageSize,
                ).items
                all += page
                startIndex += page.size
                if (page.size < pageSize || startIndex >= MAX_PAGED_ITEMS) break
            }
            all
        }
        // Server already filters via IsFolder=true; drop anything explicitly not a folder
        // as a safety net so only folders (never video files) appear in the browser.
        return items.filter { it.isFolder != false }
    }

    /** Pages through the library. A blank [parentId] means the whole (root) library. */
    suspend fun fetchAllItems(
        serverUrl: String,
        apiKey: String,
        userId: String,
        parentId: String? = null,
    ): List<BaseItemDto> {
        val api = api(serverUrl, apiKey)
        val scopedParent = parentId?.takeIf { it.isNotBlank() }
        val all = mutableListOf<BaseItemDto>()
        var startIndex = 0
        val pageSize = 200
        while (true) {
            // A short page is the sole terminator — some servers omit TotalRecordCount, and
            // stopping early would look like server-side deletions to the sync.
            val page = api.getItems(
                userId = userId,
                parentId = scopedParent,
                startIndex = startIndex,
                limit = pageSize,
            ).items
            all += page
            startIndex += page.size
            // Backstop against a broken server that ignores StartIndex and returns full pages
            // forever; no real library needs this many items.
            if (page.size < pageSize || startIndex >= MAX_PAGED_ITEMS) break
        }
        return all
    }

    suspend fun setPlayed(serverUrl: String, apiKey: String, userId: String, itemId: String, played: Boolean) {
        val api = api(serverUrl, apiKey)
        val response = if (played) api.markPlayed(userId, itemId) else api.markUnplayed(userId, itemId)
        Timber.tag(PLAYBACK_TAG).d("setPlayed(played=$played) itemId=$itemId -> HTTP ${response.status.value}")
        // A non-2xx already threw (expectSuccess = true) inside the API call, so callers' best-effort
        // /toggle failure handling still sees a failed mark-played; reaching here means success.
    }

    /**
     * Writes the resume position (and optionally the played flag / last-played time) directly to
     * the user's playstate for [itemId]. This is what lands the item in "Continue Watching".
     */
    suspend fun updatePlaybackState(
        serverUrl: String,
        apiKey: String,
        userId: String,
        itemId: String,
        positionTicks: Long,
        played: Boolean = false,
        lastPlayedDate: String? = null,
    ) {
        val response = api(serverUrl, apiKey).updateUserData(
            userId = userId,
            itemId = itemId,
            body = UserItemDataBody(
                playbackPositionTicks = positionTicks,
                played = played,
                lastPlayedDate = lastPlayedDate,
            ),
        )
        Timber.tag(PLAYBACK_TAG).d("updatePlaybackState itemId=$itemId positionTicks=$positionTicks played=$played -> HTTP ${response.status.value}")
        // A non-2xx already threw (expectSuccess = true) inside updateUserData; reaching here is success.
    }

    /** Creates a Jellyfin playlist from ordered [itemIds]; returns the new playlist id. */
    suspend fun createPlaylist(
        serverUrl: String,
        apiKey: String,
        userId: String,
        name: String,
        itemIds: List<String>,
    ): String = api(serverUrl, apiKey)
        .createPlaylist(CreatePlaylistBody(name = name, ids = itemIds, userId = userId))
        .id

    /**
     * Fetches the aggregated yt-dlp metadata index from an arbitrary URL. Throws on HTTP failure
     * — callers must be able to tell a failed fetch from an index that is genuinely empty, since
     * the former must never degrade existing index-sourced metadata.
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
