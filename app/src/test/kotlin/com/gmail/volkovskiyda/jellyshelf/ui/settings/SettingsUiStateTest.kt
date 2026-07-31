package com.gmail.volkovskiyda.jellyshelf.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two derived flags behind the metadata index URL field: whether it is offered at all, and
 * whether it is worth protecting yet. Pure state, so no ViewModel and no device.
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
}
