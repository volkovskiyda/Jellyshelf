package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.MediaFolder
import com.gmail.volkovskiyda.jellyshelf.domain.model.Session
import com.gmail.volkovskiyda.jellyshelf.domain.model.User

/**
 * The subset of Jellyfin the UI needs directly (the Settings connect/browse flow), in domain
 * terms. Sync-internal Jellyfin calls (paging items, playstate writes, playlists, the metadata
 * index) are not here — they live on the data-layer `JellyfinDataSource` and return DTOs.
 */
interface JellyfinRepository {

    /**
     * Signs in with a username and password, returning the user-scoped token. Throws on bad
     * credentials (Jellyfin answers 401) exactly like any other failed call.
     */
    suspend fun signIn(serverUrl: String, username: String, password: String): Session

    /**
     * Every other call takes the already-resolved credential — the user token when signed in, the
     * advanced API key otherwise — never a raw API key. See `Settings.credential`.
     */
    suspend fun getUsers(serverUrl: String, credential: String): List<User>

    /**
     * Immediate child folders of [parentId]. A blank [parentId] returns the user's top-level
     * collections (views); anything deeper returns that folder's subfolders.
     */
    suspend fun getChildFolders(
        serverUrl: String,
        credential: String,
        userId: String,
        parentId: String?,
    ): List<MediaFolder>
}
