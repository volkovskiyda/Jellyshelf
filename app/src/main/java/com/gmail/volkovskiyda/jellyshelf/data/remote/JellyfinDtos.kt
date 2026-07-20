package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String,
)

@JsonClass(generateAdapter = true)
data class ItemsResponse(
    @Json(name = "Items") val items: List<BaseItemDto> = emptyList(),
    @Json(name = "TotalRecordCount") val totalRecordCount: Int = 0,
)

@JsonClass(generateAdapter = true)
data class BaseItemDto(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String?,
    @Json(name = "Path") val path: String? = null,
    @Json(name = "IsFolder") val isFolder: Boolean? = null,
    @Json(name = "RunTimeTicks") val runTimeTicks: Long? = null,
    @Json(name = "ProductionYear") val productionYear: Int? = null,
    @Json(name = "Overview") val overview: String? = null,
    @Json(name = "Genres") val genres: List<String>? = null,
    @Json(name = "Tags") val tags: List<String>? = null,
    @Json(name = "ProviderIds") val providerIds: Map<String, String>? = null,
    @Json(name = "UserData") val userData: UserDataDto? = null,
)

@JsonClass(generateAdapter = true)
data class UserDataDto(
    @Json(name = "Played") val played: Boolean = false,
    @Json(name = "PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @Json(name = "PlayCount") val playCount: Int = 0,
    @Json(name = "LastPlayedDate") val lastPlayedDate: String? = null,
)

/** Response from POST /Playlists — Jellyfin's PlaylistCreationResult. */
@JsonClass(generateAdapter = true)
data class PlaylistCreationResult(
    @Json(name = "Id") val id: String,
)

/** Body for POST /Sessions/Playing/Progress — a minimal PlaybackProgressInfo. */
@JsonClass(generateAdapter = true)
data class ProgressBody(
    @Json(name = "ItemId") val itemId: String,
    @Json(name = "PositionTicks") val positionTicks: Long,
    @Json(name = "IsPaused") val isPaused: Boolean = true,
    @Json(name = "PlayMethod") val playMethod: String = "DirectPlay",
)
