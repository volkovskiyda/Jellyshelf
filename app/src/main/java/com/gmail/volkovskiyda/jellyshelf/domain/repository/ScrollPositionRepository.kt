package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.AnchorPosition
import com.gmail.volkovskiyda.jellyshelf.domain.model.ScrollPosition

/** Per-screen list scroll positions, cached in memory and mirrored to disk. */
interface ScrollPositionRepository {
    /** True once the disk seed has been merged into the in-memory cache. */
    val isSeeded: Boolean

    /** Suspends until the disk seed has been merged into the in-memory cache. */
    suspend fun awaitSeeded()

    /** Last known scroll position for [key], or [ScrollPosition.Zero] if none was saved. */
    fun peek(key: String): ScrollPosition

    /** Records [position] for [key] in memory immediately and persists it to disk. */
    fun save(key: String, position: ScrollPosition)

    /** Last known anchor position for [key], or null if none was saved. Waits for the seed. */
    suspend fun peekAnchor(key: String): AnchorPosition?

    /** Records the anchor [position] for [key] in memory immediately and persists it to disk. */
    fun saveAnchor(key: String, position: AnchorPosition)
}
