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

/**
 * Body for POST /Sessions/Playing — a minimal PlaybackStartInfo. Opening the session lets the
 * server apply its own playstate rules to everything reported afterwards; it also clears `Played`
 * and bumps `PlayCount` for a resumable item, which is the official rewatch semantics.
 */
@Serializable
data class PlaybackStartBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("CanSeek") val canSeek: Boolean = true,
)

/** Body for POST /Sessions/Playing/Progress — a minimal PlaybackProgressInfo. */
@Serializable
data class ProgressBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

/**
 * Body for POST /Sessions/Playing/Stopped — a minimal PlaybackStopInfo. The server thresholds the
 * final position (below MinResumePct it is discarded, above MaxResumePct the item is marked played
 * with no resume point), so a stop report is how the in-app player lets the server decide watched.
 */
@Serializable
data class PlaybackStopBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
)

/**
 * Body for POST /Users/{userId}/Items/{itemId}/UserData — a minimal UpdateUserItemDataDto.
 * Writing the resume position here persists it verbatim, thresholds bypassed (and surfaces the item
 * in "Continue Watching"). That is the carrier for the external-player handoff, which cannot sustain
 * the /Sessions reporting the in-app player uses: its only playstate signal is the result the player
 * returns once it is already over.
 */
@Serializable
data class UserItemDataBody(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)
