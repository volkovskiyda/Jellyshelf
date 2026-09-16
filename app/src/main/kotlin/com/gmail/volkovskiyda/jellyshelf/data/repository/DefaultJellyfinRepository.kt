package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.mapper.toDomain
import com.gmail.volkovskiyda.jellyshelf.data.mapper.toMediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository

/** Maps the DTO-returning [JellyfinDataSource] onto the UI-facing domain [JellyfinRepository]. */
class DefaultJellyfinRepository(
    private val dataSource: JellyfinDataSource,
) : JellyfinRepository {

    override suspend fun signIn(serverUrl: String, username: String, password: String): Session {
        val result = dataSource.authenticate(serverUrl, username, password)
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
