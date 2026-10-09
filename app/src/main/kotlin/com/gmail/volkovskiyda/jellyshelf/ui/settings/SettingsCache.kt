package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-lifetime Settings cache. Tab switches clear the Settings ViewModel — the back stack is
 * rebuilt as [Library, tab], which drops the Settings entry — and this holds what a visit to the
 * tab must not lose with it:
 *
 *  - the server's user list, so a visit doesn't re-query the server for its users. Entries are
 *    keyed by the server URL they came from, so a changed URL never serves another server's users;
 *  - the form's **unsaved edits** ([FormDraft]). The connection and metadata fields are only
 *    persisted by a sign-in, a Connect or a Sync now, deliberately — a typo must never overwrite a
 *    working configuration — which left anything typed before one of those at the mercy of the
 *    next tab switch. The next ViewModel starts from the draft instead of the persisted values;
 *  - whether the Advanced section was open, so the restored edits are not hidden behind a
 *    collapsed expander.
 */
class SettingsCache {
    private data class Entry(val serverUrl: String, val users: List<User>)

    /**
     * The form fields as last typed. No password: it lives in the ViewModel only until the token
     * comes back (see `SettingsViewModel.signIn`), and a process-wide slot would outlive that.
     */
    data class FormDraft(
        val serverUrl: String,
        val apiKey: String,
        val indexUrl: String,
        val metadataApiUrl: String,
        val metadataApiToken: String,
        val username: String,
    )

    private val entry = MutableStateFlow<Entry?>(null)
    private val draft = MutableStateFlow<FormDraft?>(null)
    private val advanced = MutableStateFlow(false)

    fun usersFor(serverUrl: String): List<User>? =
        entry.value?.takeIf { it.serverUrl == serverUrl }?.users

    fun store(serverUrl: String, users: List<User>) {
        entry.value = Entry(serverUrl, users)
    }

    /** The unsaved edits, or null when the form holds nothing beyond what is persisted. */
    val formDraft: FormDraft? get() = draft.value

    fun saveDraft(value: FormDraft) {
        draft.value = value
    }

    /**
     * Forgets the edits. For when the form has just been persisted — a draft kept past that point
     * would shadow the saved values, and anything that later changed them, on every visit.
     */
    fun clearDraft() {
        draft.value = null
    }

    /** Whether the Advanced section is open. */
    var advancedExpanded: Boolean
        get() = advanced.value
        set(value) {
            advanced.value = value
        }

    /**
     * Forgets everything. Only a sign-out needs this: the user entry is keyed by server URL, so a
     * *changed* URL already misses — but a sign-out clears the URL to blank and the next sign-in
     * may well retype the same one, which would otherwise hit a cache from before the wipe. The
     * draft and the expander go with it: the form is blank again, and the section it opens onto
     * is hidden until the next credentials.
     */
    fun clear() {
        entry.value = null
        draft.value = null
        advanced.value = false
    }
}
