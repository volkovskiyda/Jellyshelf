package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.User

/**
 * The subset of Jellyfin the UI needs directly (the Settings connect/browse flow), in domain
 * terms. Sync-internal Jellyfin calls (paging items, playstate writes, playlists, the metadata
 * index) are not here — they live on the data-layer `JellyfinDataSource` and return DTOs.
 */
interface JellyfinRepository {
    suspend fun getUsers(serverUrl: String, apiKey: String): List<User>

    /**
     * Immediate child folders of [parentId]. A blank [parentId] returns the user's top-level
     * collections (views); anything deeper returns that folder's subfolders.
     */
    suspend fun getChildFolders(
        serverUrl: String,
        apiKey: String,
        userId: String,
        parentId: String?,
    ): List<MediaFolder>
}
