package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.mapper.toDomain
import com.gmail.volkovskiyda.jellyshelf.data.mapper.toMediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.mediaBrowserAuthHeader
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository

/** Maps the DTO-returning [JellyfinDataSource] onto the UI-facing domain [JellyfinRepository]. */
class DefaultJellyfinRepository(
    private val dataSource: JellyfinDataSource,
    private val settings: SettingsRepository,
    private val deviceInfo: DeviceInfo,
) : JellyfinRepository {

    override suspend fun signIn(serverUrl: String, username: String, password: String): Session {
        // The device id is read (and generated on first use) here rather than injected, so the
        // header is assembled in exactly one place for the one call that needs it.
        val authorization = mediaBrowserAuthHeader(deviceInfo, settings.deviceId())
        val result = dataSource.authenticate(serverUrl, username, password, authorization)
        return Session(accessToken = result.accessToken, user = result.user.toDomain())
    }

    override suspend fun getUsers(serverUrl: String, credential: String): List<User> =
        dataSource.getUsers(serverUrl, credential).map { it.toDomain() }

    override suspend fun getChildFolders(
        serverUrl: String,
        credential: String,
        userId: String,
        parentId: String?,
    ): List<MediaFolder> =
        dataSource.getChildFolders(serverUrl, credential, userId, parentId).map { it.toMediaFolder() }
}
