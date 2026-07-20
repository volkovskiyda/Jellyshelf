package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.CreatePlaylistBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinApi
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.ProgressBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserItemDataBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class JellyfinRepository(
    private val client: JellyfinClient,
    private val okHttpClient: OkHttpClient,
    moshi: Moshi,
) {
    private val indexAdapter = moshi.adapter<List<IndexEntry>>(
        Types.newParameterizedType(List::class.java, IndexEntry::class.java)
    )

    // Cache the built API by "url|key" so we don't rebuild Retrofit each call. A single volatile
    // pair keeps the key and its API published atomically, so a concurrent settings change can
    // never pair one server's key with another server's client.
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
                if (page.size < pageSize) break
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
            if (page.size < pageSize) break
        }
        return all
    }

    suspend fun setPlayed(serverUrl: String, apiKey: String, userId: String, itemId: String, played: Boolean) {
        val api = api(serverUrl, apiKey)
        if (played) api.markPlayed(userId, itemId) else api.markUnplayed(userId, itemId)
    }

    suspend fun reportProgress(serverUrl: String, apiKey: String, itemId: String, positionTicks: Long) {
        api(serverUrl, apiKey).reportProgress(ProgressBody(itemId = itemId, positionTicks = positionTicks))
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
        api(serverUrl, apiKey).updateUserData(
            userId = userId,
            itemId = itemId,
            body = UserItemDataBody(
                playbackPositionTicks = positionTicks,
                played = played,
                lastPlayedDate = lastPlayedDate,
            ),
        )
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
    suspend fun fetchIndex(indexUrl: String): List<IndexEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(indexUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Index fetch failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            // A legitimate index is always a JSON array (build-library-index.sh emits "[]" at
            // minimum). A blank 200 — captive portal, file caught mid-rewrite — must count as a
            // failed fetch, or it would downgrade every index-sourced row.
            if (body.isBlank()) throw IOException("Index fetch returned an empty body")
            indexAdapter.fromJson(body).orEmpty()
        }
    }
}
