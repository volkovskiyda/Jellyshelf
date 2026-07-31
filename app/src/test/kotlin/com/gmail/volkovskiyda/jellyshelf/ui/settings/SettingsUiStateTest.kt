package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_USER_ID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived flags behind the metadata index URL field — whether it is offered at all, and
 * whether it is worth protecting yet — and behind the demo affordances. Pure state, so no
 * ViewModel and no device.
 */
class SettingsUiStateTest {

    @Test
    fun `the index field stays hidden until there are credentials`() {
        assertFalse(SettingsUiState().canEditIndex)
        assertFalse(SettingsUiState(serverUrl = "https://example.org").canEditIndex)
    }

    @Test
    fun `signing in reveals the index field`() {
        assertTrue(SettingsUiState(signedIn = true).canEditIndex)
    }

    @Test
    fun `an API key reveals it too`() {
        // The advanced path authenticates without a user token, and still needs to sync.
        assertTrue(SettingsUiState(apiKey = "key").canEditIndex)
    }

    @Test
    fun `nothing is protected before the first sync`() {
        assertFalse(SettingsUiState(signedIn = true).indexProtected)
    }

    @Test
    fun `a completed sync protects the field`() {
        assertTrue(SettingsUiState(signedIn = true, lastSyncAt = 1L).indexProtected)
    }

    @Test
    fun `a sync in flight protects it before it has ever completed`() {
        assertTrue(SettingsUiState(signedIn = true, syncRunning = true).indexProtected)
    }

    @Test
    fun `other settings work does not protect it`() {
        // busy covers sign-in and folder browsing too; only a sync means there is a populated
        // library to damage.
        assertFalse(SettingsUiState(signedIn = true, busy = true).indexProtected)
    }

    // --- the demo affordances ---

    @Test
    fun `a fresh install is offered the demo`() {
        assertTrue(SettingsUiState().canTryDemo)
    }

    @Test
    fun `a configured install is not`() {
        // Either credential means there is a real server to sync from, which is the better offer.
        assertFalse(SettingsUiState(signedIn = true).canTryDemo)
        assertFalse(SettingsUiState(apiKey = "key").canTryDemo)
    }

    @Test
    fun `an install already in demo mode is not offered it again`() {
        // The way out is Reset local data, not a second tap of the same button.
        assertFalse(SettingsUiState(demoMode = true).canTryDemo)
    }

    @Test
    fun `the sync scope hides behind the demo user`() {
        // A real user id opens the scope section; the demo server's fake one has no item tree.
        assertFalse(SettingsUiState(selectedUserId = "user-id").demoUserSelected)
        assertTrue(SettingsUiState(selectedUserId = DEMO_USER_ID).demoUserSelected)
    }
}
