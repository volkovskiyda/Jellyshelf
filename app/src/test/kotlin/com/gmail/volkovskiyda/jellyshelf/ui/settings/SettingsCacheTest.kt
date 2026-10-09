package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the process-lifetime cache keeps and what each kind of forgetting drops. The ViewModel
 * side — which edits write a draft and which events clear it — is instrumented, in
 * `SettingsFormDraftInstrumentedTest`, because the ViewModel needs an `Application`.
 */
class SettingsCacheTest {

    private val draft = SettingsCache.FormDraft(
        serverUrl = "https://example.org",
        apiKey = "",
        indexUrl = "https://db.example.org/index.json",
        metadataApiUrl = "https://db.example.org/api",
        metadataApiToken = "secret",
        username = "alice",
    )

    @Test
    fun `starts with nothing`() {
        val cache = SettingsCache()
        assertNull(cache.formDraft)
        assertFalse(cache.advancedExpanded)
    }

    @Test
    fun `the latest draft is the one returned`() {
        val cache = SettingsCache()
        cache.saveDraft(draft.copy(username = "first"))
        cache.saveDraft(draft)
        assertEquals(draft, cache.formDraft)
    }

    @Test
    fun `clearing the draft leaves the users and the expander alone`() {
        val cache = SettingsCache()
        cache.store("https://example.org", listOf(User(id = "u1", name = "alice")))
        cache.advancedExpanded = true
        cache.saveDraft(draft)

        cache.clearDraft()

        assertNull(cache.formDraft)
        assertTrue(cache.advancedExpanded)
        assertEquals(1, cache.usersFor("https://example.org")?.size)
    }

    @Test
    fun `a sign-out forgets everything`() {
        val cache = SettingsCache()
        cache.store("https://example.org", listOf(User(id = "u1", name = "alice")))
        cache.advancedExpanded = true
        cache.saveDraft(draft)

        cache.clear()

        assertNull(cache.formDraft)
        assertFalse(cache.advancedExpanded)
        assertNull(cache.usersFor("https://example.org"))
    }
}
