package com.gmail.volkovskiyda.jellyshelf.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

/**
 * Body for POST /Users/AuthenticateByName. `Pw` is the plaintext password over (ideally) HTTPS —
 * Jellyfin's own scheme. It is never persisted: only the returned token is.
 */
@Serializable
data class AuthenticateByNameBody(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

/** Response from POST /Users/AuthenticateByName — the user-scoped token plus who it belongs to. */
@Serializable
data class AuthenticationResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class ItemsResponse(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String?,
    @SerialName("Path") val path: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Tags") val tags: List<String>? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
)

@Serializable
data class UserDataDto(
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

/** Response from POST /Playlists — Jellyfin's PlaylistCreationResult. */
@Serializable
data class PlaylistCreationResult(
    @SerialName("Id") val id: String,
)

/**
 * Body for POST /Playlists — a minimal CreatePlaylistDto. Item ids travel in the JSON body
 * (not the query string), so large categories can't overflow URL length limits.
 */
@Serializable
data class CreatePlaylistBody(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
    @SerialName("UserId") val userId: String,
    @SerialName("MediaType") val mediaType: String = "Video",
)

/** Body for POST /Sessions/Playing/Progress — a minimal PlaybackProgressInfo. */
@Serializable
data class ProgressBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

/**
 * Body for POST /Users/{userId}/Items/{itemId}/UserData — a minimal UpdateUserItemDataDto.
 * Writing the resume position here persists it directly (and surfaces the item in "Continue
 * Watching"), unlike /Sessions/Playing/Stopped which only commits playstate for a live,
 * progress-tracked session — impossible to sustain once playback is handed to an external player.
 */
@Serializable
data class UserItemDataBody(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)
