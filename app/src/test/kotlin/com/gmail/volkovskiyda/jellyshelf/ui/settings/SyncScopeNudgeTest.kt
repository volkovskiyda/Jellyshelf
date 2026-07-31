package com.gmail.volkovskiyda.jellyshelf.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole truth table of the sync-scope nudge: a first **Sync now** at the default scope points
 * at the scope section instead of pulling the entire server, and nothing else does.
 */
class SyncScopeNudgeTest {

    private val folder = "folder-id"

    @Test
    fun `the first sync at the default scope nudges`() {
        assertTrue(shouldNudgeSyncScope(ROOT_SCOPE_ID, alreadyNudged = false))
    }

    @Test
    fun `the second tap syncs everything, as asked`() {
        assertFalse(shouldNudgeSyncScope(ROOT_SCOPE_ID, alreadyNudged = true))
    }

    @Test
    fun `a chosen folder never nudges`() {
        // Picking a scope is the thing the nudge exists to prompt; having done it, there is
        // nothing to point out.
        assertFalse(shouldNudgeSyncScope(folder, alreadyNudged = false))
        assertFalse(shouldNudgeSyncScope(folder, alreadyNudged = true))
    }
}
