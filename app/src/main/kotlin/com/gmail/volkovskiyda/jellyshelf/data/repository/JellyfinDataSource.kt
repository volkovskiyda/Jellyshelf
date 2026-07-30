package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.remote.AuthenticateByNameBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.AuthenticationResult
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.CreatePlaylistBody
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinApi
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserItemDataBody
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

/** Paging safety cap — far above any real library, purely an infinite-loop backstop. */
private const val MAX_PAGED_ITEMS = 1_000_000

/** Page size for browsing folders — folder listings are small, so a bigger page is cheap. */
private const val FOLDER_PAGE_SIZE = 500

/** Page size for the full item listing used by sync. */
private const val ITEM_PAGE_SIZE = 200

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
) {
    // Cache the built API by "url|key" so we don't reconfigure the client each call. A single
    // atomically-swapped pair keeps the key and its API published together, so a concurrent
    // settings change can never pair one server's key with another server's client.
    private data class CachedApi(val key: String, val api: JellyfinApi)

    private val cached = MutableStateFlow<CachedApi?>(null)

    private fun api(serverUrl: String, credential: String): JellyfinApi {
        val key = "$serverUrl|$credential"
        cached.value?.let { if (it.key == key) return it.api }
        return client.create(serverUrl, credential).also { cached.value = CachedApi(key, it) }
    }

    /**
     * Exchanges a password for a user-scoped token. The only call that takes credentials rather
     * than a [Settings.credential][com.gmail.volkovskiyda.jellyshelf.domain.model.Settings]: there
     * is nothing to authenticate with yet, so it passes a blank one and identifies the app through
     * the `MediaBrowser` header instead.
     */
    suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: String,
        authorization: String,
    ): AuthenticationResult = api(serverUrl, credential = "").authenticateByName(
        AuthenticateByNameBody(username = username, password = password),
        authorization = authorization,
    )

    suspend fun getUsers(serverUrl: String, credential: String): List<UserDto> =
        api(serverUrl, credential).getUsers()

    /** Top-level libraries/collections for the user. */
    suspend fun getViews(serverUrl: String, credential: String, userId: String): List<BaseItemDto> =
        api(serverUrl, credential).getViews(userId).items

    /**
     * Immediate child folders of [parentId]. A blank [parentId] returns the user's
     * top-level collections (views); anything deeper returns that folder's subfolders.
     */
    suspend fun getChildFolders(
        serverUrl: String,
        credential: String,
        userId: String,
        parentId: String?,
    ): List<BaseItemDto> {
        val api = api(serverUrl, credential)
        val pid = parentId?.takeIf { it.isNotBlank() }
        val items = if (pid == null) {
            api.getViews(userId).items
        } else {
            // Page through so a folder with more than one page of children isn't silently
            // truncated in the browser. A short page is the sole terminator — some servers
            // omit TotalRecordCount, and a full page with total=0 must not stop the loop.
            val all = mutableListOf<BaseItemDto>()
            val pageSize = FOLDER_PAGE_SIZE
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
        credential: String,
        userId: String,
        parentId: String? = null,
    ): List<BaseItemDto> {
        val api = api(serverUrl, credential)
        val scopedParent = parentId?.takeIf { it.isNotBlank() }
        val all = mutableListOf<BaseItemDto>()
        var startIndex = 0
        val pageSize = ITEM_PAGE_SIZE
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

    suspend fun setPlayed(serverUrl: String, credential: String, userId: String, itemId: String, played: Boolean) {
        val api = api(serverUrl, credential)
        val response = api.setPlayed(userId, itemId, played)
        Timber.tag(PLAYBACK_TAG).d("setPlayed(played=$played) itemId=$itemId -> HTTP ${response.status.value}")
        // A non-2xx already threw (expectSuccess = true) inside the API call, so callers' best-effort
        // /toggle failure handling still sees a failed mark-played; reaching here means success.
    }

    /**
     * Deletes [itemId] from the server library, its media file included. Throws on a non-2xx —
     * callers must be able to tell a refused delete (no rights, item already gone) from a done
     * one, since only a confirmed server delete makes dropping the local row safe.
     */
    suspend fun deleteItem(serverUrl: String, credential: String, itemId: String) {
        api(serverUrl, credential).deleteItem(itemId)
        // A non-2xx already threw (expectSuccess = true); reaching here means the item is gone.
    }

    /**
     * Writes the resume position (and optionally the played flag / last-played time) directly to
     * the user's playstate for [itemId]. This is what lands the item in "Continue Watching".
     */
    suspend fun updatePlaybackState(
        serverUrl: String,
        credential: String,
        userId: String,
        itemId: String,
        positionTicks: Long,
        played: Boolean = false,
        lastPlayedDate: String? = null,
    ) {
        val response = api(serverUrl, credential).updateUserData(
            userId = userId,
            itemId = itemId,
            body = UserItemDataBody(
                playbackPositionTicks = positionTicks,
                played = played,
                lastPlayedDate = lastPlayedDate,
            ),
        )
        Timber.tag(PLAYBACK_TAG).d(
            "updatePlaybackState itemId=$itemId positionTicks=$positionTicks played=$played -> " +
                "HTTP ${response.status.value}",
        )
        // A non-2xx already threw (expectSuccess = true) inside updateUserData; reaching here is success.
    }

    /** Creates a Jellyfin playlist from ordered [itemIds]; returns the new playlist id. */
    suspend fun createPlaylist(
        serverUrl: String,
        credential: String,
        userId: String,
        name: String,
        itemIds: List<String>,
    ): String = api(serverUrl, credential)
        .createPlaylist(CreatePlaylistBody(name = name, ids = itemIds, userId = userId))
        .id
}
