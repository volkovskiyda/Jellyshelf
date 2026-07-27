package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.AnchorPosition
import com.gmail.volkovskiyda.jellyshelf.domain.model.ScrollPosition
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository

/**
 * In-memory [ScrollPositionRepository] for host-side rendering — no DataStore, no disk, and
 * already seeded so nothing suspends waiting for a read that will never happen.
 *
 * Lives in `src/testShared`, which both the `test` and `screenshotTest` source sets compile (see
 * app/build.gradle.kts): behavior tests and screenshot previews need the same stand-in.
 */
class FakeScrollPositionRepository(
    private val positions: MutableMap<String, ScrollPosition> = mutableMapOf(),
    private val anchors: MutableMap<String, AnchorPosition> = mutableMapOf(),
) : ScrollPositionRepository {
    override val isSeeded: Boolean = true

    override suspend fun awaitSeeded() = Unit

    override fun peek(key: String): ScrollPosition = positions[key] ?: ScrollPosition.Zero

    override fun save(key: String, position: ScrollPosition) {
        positions[key] = position
    }

    override suspend fun peekAnchor(key: String): AnchorPosition? = anchors[key]

    override fun saveAnchor(key: String, position: AnchorPosition) {
        anchors[key] = position
    }
}
