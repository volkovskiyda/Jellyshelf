package com.gmail.volkovskiyda.jellyshelf.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface JellyfinApi {

    @GET("Users")
    suspend fun getUsers(): List<UserDto>

    /** Top-level libraries/collections visible to the user (Movies, Home Videos, …). */
    @GET("Users/{userId}/Views")
    suspend fun getViews(@Path("userId") userId: String): ItemsResponse

    /** Immediate child folders of a given item — one level, for the folder browser. */
    @GET("Items")
    suspend fun getChildFolders(
        @Query("userId") userId: String,
        @Query("ParentId") parentId: String,
        @Query("IsFolder") isFolder: Boolean = true,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("Fields") fields: String = "Path",
        @Query("Limit") limit: Int = 500,
    ): ItemsResponse

    @GET("Items")
    suspend fun getItems(
        @Query("userId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("Recursive") recursive: Boolean = true,
        @Query("IncludeItemTypes") includeItemTypes: String = "Video,Movie,Episode,MusicVideo",
        @Query("Fields") fields: String = "Path,ProviderIds,Overview,Genres,Tags,ProductionYear",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 200,
    ): ItemsResponse

    @POST("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markPlayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    ): Response<Unit>

    @DELETE("Users/{userId}/PlayedItems/{itemId}")
    suspend fun markUnplayed(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    ): Response<Unit>

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: ProgressBody): Response<Unit>

    /** Writes user-scoped playstate (resume position / played flag) directly to the item. */
    @POST("Users/{userId}/Items/{itemId}/UserData")
    suspend fun updateUserData(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Body body: UserItemDataBody,
    ): Response<Unit>

    /**
     * Creates a playlist. The playlist keeps the order of [ids] (comma-separated item ids),
     * so pass them pre-sorted. Returns the new playlist's id.
     */
    @POST("Playlists")
    suspend fun createPlaylist(
        @Query("name") name: String,
        @Query("ids") ids: String,
        @Query("userId") userId: String,
        @Query("mediaType") mediaType: String = "Video",
    ): PlaylistCreationResult
}
