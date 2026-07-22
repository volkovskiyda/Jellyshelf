package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.mapper.toDomain
import com.gmail.volkovskiyda.jellyshelf.data.mapper.toMediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository

/** Maps the DTO-returning [JellyfinDataSource] onto the UI-facing domain [JellyfinRepository]. */
class DefaultJellyfinRepository(
    private val dataSource: JellyfinDataSource,
) : JellyfinRepository {

    override suspend fun getUsers(serverUrl: String, apiKey: String): List<User> =
        dataSource.getUsers(serverUrl, apiKey).map { it.toDomain() }

    override suspend fun getChildFolders(
        serverUrl: String,
        apiKey: String,
        userId: String,
        parentId: String?,
    ): List<MediaFolder> =
        dataSource.getChildFolders(serverUrl, apiKey, userId, parentId).map { it.toMediaFolder() }
}
