package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinApi
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.ProgressBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
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

    // Cache the built API by "url|key" so we don't rebuild Retrofit each call.
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedApi: JellyfinApi? = null

    private fun api(serverUrl: String, apiKey: String): JellyfinApi {
        val key = "$serverUrl|$apiKey"
        cachedApi?.let { if (key == cachedKey) return it }
        return client.create(serverUrl, apiKey).also {
            cachedApi = it
            cachedKey = key
        }
    }

    suspend fun getUsers(serverUrl: String, apiKey: String): List<UserDto> =
        api(serverUrl, apiKey).getUsers()

    /** Pages through the whole library. */
    suspend fun fetchAllItems(serverUrl: String, apiKey: String, userId: String): List<BaseItemDto> {
        val api = api(serverUrl, apiKey)
        val all = mutableListOf<BaseItemDto>()
        var startIndex = 0
        val pageSize = 200
        while (true) {
            val page = api.getItems(userId = userId, startIndex = startIndex, limit = pageSize)
            all += page.items
            startIndex += page.items.size
            if (page.items.size < pageSize || startIndex >= page.totalRecordCount) break
            if (page.items.isEmpty()) break
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

    /** Fetches the aggregated yt-dlp metadata index from an arbitrary URL. */
    suspend fun fetchIndex(indexUrl: String): List<IndexEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(indexUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) emptyList() else indexAdapter.fromJson(body).orEmpty()
        }
    }
}
