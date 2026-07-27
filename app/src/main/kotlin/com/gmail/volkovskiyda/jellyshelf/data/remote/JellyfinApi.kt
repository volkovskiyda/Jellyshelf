package com.gmail.volkovskiyda.jellyshelf.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * Hand-written Ktor client for the Jellyfin REST endpoints. Wraps a per-server-configured
 * [HttpClient] (base URL + X-Emby-Token + Accept applied via DefaultRequest in [JellyfinClient]).
 * Relative paths resolve against that base; [parameter] skips null values, so a null `parentId`
 * omits its query param. The playstate writes return Ktor's [HttpResponse]; with the base client's
 * `expectSuccess = true`, a non-2xx already threw before the caller sees it.
 */
class JellyfinApi(private val client: HttpClient) {

    /**
     * Exchanges a username and password for a user-scoped access token.
     *
     * [authorization] is the `MediaBrowser …` header (see
     * [com.gmail.volkovskiyda.jellyshelf.domain.mediaBrowserAuthHeader]): Jellyfin rejects the
     * login without it, and there is no token yet for `X-Emby-Token` to carry. A wrong
     * username/password comes back 401, which `expectSuccess = true` turns into a throw.
     */
    suspend fun authenticateByName(
        body: AuthenticateByNameBody,
        authorization: String,
    ): AuthenticationResult = client.post("Users/AuthenticateByName") {
        header(HttpHeaders.Authorization, authorization)
        contentType(ContentType.Application.Json)
        setBody(body)
    }.body()

    suspend fun getUsers(): List<UserDto> =
        client.get("Users").body()

    /** Top-level libraries/collections visible to the user (Movies, Home Videos, …). */
    suspend fun getViews(userId: String): ItemsResponse =
        client.get("Users/$userId/Views").body()

    /** Immediate child folders of a given item — one level, for the folder browser. */
    suspend fun getChildFolders(
        userId: String,
        parentId: String,
        isFolder: Boolean = true,
        sortBy: String = "SortName",
        fields: String = "Path",
        startIndex: Int = 0,
        limit: Int = 500,
    ): ItemsResponse = client.get("Items") {
        parameter("userId", userId)
        parameter("ParentId", parentId)
        parameter("IsFolder", isFolder)
        parameter("SortBy", sortBy)
        parameter("Fields", fields)
        parameter("StartIndex", startIndex)
        parameter("Limit", limit)
    }.body()

    suspend fun getItems(
        userId: String,
        parentId: String? = null,
        recursive: Boolean = true,
        includeItemTypes: String = "Video,Movie,Episode,MusicVideo",
        fields: String = "Path,ProviderIds,Overview,Genres,Tags,ProductionYear",
        // Stable ordering matters: paging without a sort can skip items when the library
        // changes mid-sync, and a skipped item now gets deleted locally by the sync.
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        startIndex: Int = 0,
        limit: Int = 200,
    ): ItemsResponse = client.get("Items") {
        parameter("userId", userId)
        parameter("ParentId", parentId) // null -> param omitted
        parameter("Recursive", recursive)
        parameter("IncludeItemTypes", includeItemTypes)
        parameter("Fields", fields)
        parameter("SortBy", sortBy)
        parameter("SortOrder", sortOrder)
        parameter("StartIndex", startIndex)
        parameter("Limit", limit)
    }.body()

    suspend fun markPlayed(userId: String, itemId: String): HttpResponse =
        client.post("Users/$userId/PlayedItems/$itemId")

    suspend fun markUnplayed(userId: String, itemId: String): HttpResponse =
        client.delete("Users/$userId/PlayedItems/$itemId")

    /**
     * Deletes an item from the library, its media file included — Jellyfin has no trash to
     * recover it from. The server answers 401/403 unless the user has deletion rights, which
     * `expectSuccess = true` turns into a throw.
     */
    suspend fun deleteItem(itemId: String): HttpResponse =
        client.delete("Items/$itemId")

    suspend fun reportProgress(body: ProgressBody): HttpResponse =
        client.post("Sessions/Playing/Progress") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Writes user-scoped playstate (resume position / played flag) directly to the item. */
    suspend fun updateUserData(userId: String, itemId: String, body: UserItemDataBody): HttpResponse =
        client.post("Users/$userId/Items/$itemId/UserData") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /**
     * Creates a playlist. The playlist keeps the order of the body's ids, so pass them
     * pre-sorted. Returns the new playlist's id.
     */
    suspend fun createPlaylist(body: CreatePlaylistBody): PlaylistCreationResult =
        client.post("Playlists") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
}
