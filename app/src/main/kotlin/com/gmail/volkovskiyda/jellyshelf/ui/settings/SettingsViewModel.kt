package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncWorker
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import com.gmail.volkovskiyda.jellyshelf.domain.repository.JellyfinRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.util.CLEARTEXT_BLOCKED_MESSAGE
import com.gmail.volkovskiyda.jellyshelf.util.SESSION_EXPIRED_MESSAGE
import com.gmail.volkovskiyda.jellyshelf.util.isCleartextBlocked
import com.gmail.volkovskiyda.jellyshelf.util.isUnauthorized
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sentinel for the "all collections" (root) scope. */
const val ROOT_SCOPE_ID = ""

/**
 * Persisted name of the root scope. Blank — the UI substitutes the localized "all collections"
 * label at render time, so the stored value can't freeze in whatever language it was saved in.
 */
const val ROOT_SCOPE_PATH = ""
private const val CRUMB_SEPARATOR = " › "

/** A folder in the Jellyfin item tree. */
data class FolderRef(val id: String, val name: String, val path: String?)

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val indexUrl: String = "",
    // Sign-in fields. The password lives here only until the token comes back — see [signIn].
    val username: String = "",
    val password: String = "",
    /** True once a user token is held: the default, user-scoped auth path. */
    val signedIn: Boolean = false,
    /** Advanced playback handoff: credential in the URL query rather than an intent header. */
    val tokenInQuery: Boolean = false,
    val users: List<User> = emptyList(),
    val selectedUserId: String = "",
    val selectedUserName: String = "",
    // Persisted sync scope. A blank path means the root ("all collections") scope.
    val selectedScopeId: String = ROOT_SCOPE_ID,
    val selectedScopePath: String = ROOT_SCOPE_PATH,
    // Ephemeral folder-browser state.
    val browserOpen: Boolean = false,
    val breadcrumb: List<FolderRef> = emptyList(),
    val childFolders: List<FolderRef> = emptyList(),
    val loadingFolders: Boolean = false,
    val busy: Boolean = false,
    val status: String? = null,
    val statusIsError: Boolean = false,
    val lastSyncAt: Long = 0L,
) {
    /** ParentId of the folder currently being browsed ("" == root). */
    val currentParentId: String get() = breadcrumb.lastOrNull()?.id ?: ROOT_SCOPE_ID

    /**
     * Path of the folder currently being browsed, from server-provided folder names — no
     * localized root prefix, so it is safe to persist. Blank at the root.
     */
    val currentPath: String get() = breadcrumb.joinToString(CRUMB_SEPARATOR) { it.name }
}

/** Sync progress as owned by WorkManager, merged into [SettingsUiState] for display. */
private data class SyncUi(val running: Boolean, val message: String?, val isError: Boolean)

class SettingsViewModel(
    private val app: Application,
    private val settingsRepo: SettingsRepository,
    private val libraryRepo: LibraryRepository,
    private val jellyfin: JellyfinRepository,
    private val settingsCache: SettingsCache,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    /** Local operations (connect, reset) only — sync lives in [_sync], see [state]. */
    private val _state = MutableStateFlow(SettingsUiState())
    private val _sync = MutableStateFlow<SyncUi?>(null)

    /**
     * Sync no longer runs in this scope, so its progress can't be held in [_state]: the worker
     * outlives the ViewModel a tab switch clears. Merging the two here keeps the screen's
     * contract unchanged while a sync started on one visit still reports on the next.
     */
    val state: StateFlow<SettingsUiState> = combine(_state, _sync) { local, sync ->
        if (sync == null) local
        else local.copy(
            busy = local.busy || sync.running,
            // A local operation's own message wins while it runs — a sync finishing in the
            // middle of a connect must not overwrite "Connecting…".
            status = if (local.busy) local.status else sync.message ?: local.status,
            statusIsError = if (local.busy) local.statusIsError else sync.isError,
        )
    }.stateIn(viewModelScope, WhileUiSubscribed, SettingsUiState())

    val videoCount: StateFlow<Int> = libraryRepo.videoCount()
        .stateIn(viewModelScope, WhileUiSubscribed, 0)

    /** Set as soon as the user edits any connection field, see [init]. */
    private var fieldsEdited = false

    init {
        viewModelScope.launch {
            val s = settingsRepo.snapshot()
            val cur = _state.value
            // This snapshot loads asynchronously — don't clobber text the user managed to type
            // into the fields before it landed.
            val edited = fieldsEdited
            val serverUrl = if (edited) cur.serverUrl else s.serverUrl
            // Looked up by the URL the field actually shows, not by the persisted one: entries
            // are keyed by the server they came from, so an edited URL misses the cache and the
            // chips stay cleared — which is what onServerUrlChange asked for. Restoring the old
            // server's users here would let a tap persist a foreign user id under the new URL.
            val cachedUsers = settingsCache.usersFor(serverUrl.trim())
            _state.value = cur.copy(
                serverUrl = serverUrl,
                apiKey = if (edited) cur.apiKey else s.apiKey,
                indexUrl = if (edited) cur.indexUrl else s.indexUrl,
                username = if (edited) cur.username else s.userName,
                signedIn = s.isSignedIn,
                tokenInQuery = s.tokenInQuery,
                users = cachedUsers.orEmpty(),
                // Still the persisted user and scope: they are what sync uses until the next
                // Connect, so the screen would lie by blanking them over an unsaved edit.
                selectedUserId = s.userId,
                selectedUserName = s.userName,
                selectedScopeId = s.libraryId,
                // Legacy installs persisted the English root prefix; strip it so the UI can
                // localize the root label.
                selectedScopePath = s.libraryName
                    .removePrefix("All collections$CRUMB_SEPARATOR")
                    .removePrefix("All collections"),
                lastSyncAt = s.lastSyncAt,
            )
            // Only hit the server when this process hasn't loaded users yet — tab switches
            // recreate this ViewModel, and re-connecting on every visit is wasted work. A
            // user already editing the fields connects explicitly with what they typed.
            // Signed-in installs skip it entirely: the token already identifies the user, so
            // there is no server-wide user list to fetch or pick from.
            val needsUserList = s.hasCredentials && !s.isSignedIn
            if (needsUserList && cachedUsers == null && !edited) connect(silent = true)
        }
        observeSync()
    }

    /**
     * Mirrors the manual sync worker's state into [_sync]. Re-attaching on every ViewModel
     * creation is what makes sync status survive a tab switch — WorkManager replays the current
     * state of the unique work, finished or not.
     */
    private fun observeSync() {
        syncScheduler.manualSyncInfo()
            .mapNotNull { it.lastOrNull() }
            .onEach { info ->
                _sync.value = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                        SyncUi(running = true, message = app.getString(R.string.syncing), isError = false)
                    WorkInfo.State.SUCCEEDED -> {
                        val out = info.outputData
                        // The worker exits early without output when credentials are missing;
                        // showing a 0/0 summary then would be a lie, so say nothing.
                        val message = if (out.keyValueMap.isEmpty()) null else {
                            val summary = app.getString(
                                R.string.sync_summary,
                                out.getInt(SyncWorker.KEY_INDEXED, 0),
                                out.getInt(SyncWorker.KEY_MATCHED, 0),
                                out.getInt(SyncWorker.KEY_CATEGORIES, 0),
                            )
                            // The sync succeeded, but without the metadata index its counts are
                            // the reason "nothing new gets categorized" — say so rather than
                            // reporting an unqualified success.
                            val qualified = if (out.getBoolean(SyncWorker.KEY_INDEX_DEGRADED, false)) {
                                app.getString(R.string.sync_index_unavailable, summary)
                            } else {
                                summary
                            }
                            appendAutoFill(
                                qualified,
                                filled = out.getInt(SyncWorker.KEY_AUTO_FILLED, 0),
                                failed = out.getInt(SyncWorker.KEY_AUTO_FILL_FAILED, 0),
                            )
                        }
                        _state.value = _state.value.copy(lastSyncAt = settingsRepo.snapshot().lastSyncAt)
                        SyncUi(running = false, message = message, isError = false)
                    }
                    WorkInfo.State.FAILED -> {
                        // A sync that hit a rejected token already cleared the session in the data
                        // layer; re-read it so the screen switches back to the sign-in form
                        // instead of still claiming to be signed in.
                        _state.value = _state.value.copy(signedIn = settingsRepo.snapshot().isSignedIn)
                        SyncUi(
                            running = false,
                            message = info.outputData.getString(SyncWorker.KEY_ERROR)
                                ?: app.getString(R.string.unknown_error),
                            isError = true,
                        )
                    }
                    // Cancelled by "Reset local data", which posts its own status — leave it be.
                    WorkInfo.State.CANCELLED -> null
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Appends what the sync's yt-dlp auto-fill pass managed, when it ran at all. Silent on a
     * 0/0 pass — the common case is a library with no gaps, and reporting "filled 0" on every
     * sync would train the user to stop reading the line.
     */
    private fun appendAutoFill(summary: String, filled: Int, failed: Int): String = when {
        filled == 0 && failed == 0 -> summary
        failed == 0 -> app.getString(R.string.sync_auto_filled, summary, filled)
        else -> app.getString(R.string.sync_auto_filled_partial, summary, filled, failed)
    }

    /**
     * The half of a failure message that names the cause. A cleartext block gets an explanation
     * instead of its raw exception text — "CLEARTEXT communication to 192.168.1.10 not permitted
     * by network security policy" tells a user nothing about the `https://` they actually need.
     */
    private fun reason(e: Throwable): String = when {
        isCleartextBlocked(e) -> CLEARTEXT_BLOCKED_MESSAGE
        isUnauthorized(e) && _state.value.signedIn -> SESSION_EXPIRED_MESSAGE
        else -> e.message ?: app.getString(R.string.unknown_error)
    }

    /**
     * Drops the session when the server rejected the token, so the screen asks for a sign-in
     * rather than leaving a dead credential in place. Never falls back to the advanced API key:
     * silently restoring full-server access is exactly what user login exists to avoid.
     */
    private suspend fun clearSessionIfRejected(e: Throwable) {
        if (!isUnauthorized(e) || !_state.value.signedIn) return
        settingsRepo.clearSession()
        _state.value = _state.value.copy(signedIn = false, selectedUserId = "", selectedUserName = "")
    }

    fun onServerUrlChange(value: String) {
        fieldsEdited = true
        // A different server has different users — drop the chips until the next connect so
        // a stale selection can't be persisted against the new URL.
        _state.value = _state.value.copy(serverUrl = value, users = emptyList())
    }
    fun onApiKeyChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(apiKey = value)
    }
    fun onIndexUrlChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(indexUrl = value)
    }

    /**
     * Advanced: force the credential into the stream URL for players that ignore the headers
     * extra. Persisted immediately — it isn't part of the connect/sign-in form.
     */
    fun onTokenInQueryChange(enabled: Boolean) {
        _state.value = _state.value.copy(tokenInQuery = enabled)
        viewModelScope.launch { settingsRepo.setTokenInQuery(enabled) }
    }

    fun onUsernameChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(username = value)
    }

    fun onPasswordChange(value: String) {
        fieldsEdited = true
        _state.value = _state.value.copy(password = value)
    }

    /**
     * The primary connect flow: exchange username + password for a user-scoped token.
     *
     * The password is cleared from state the moment the call returns — success or failure — so it
     * never outlives the request that used it, and it is never written to DataStore at all.
     */
    fun signIn() {
        val s = _state.value
        // `busy` is a frame stale in the UI; guard here so two taps can't run concurrent sign-ins.
        if (s.busy) return
        if (s.serverUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(
                status = app.getString(R.string.enter_server_user_password),
                statusIsError = true,
            )
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(
                busy = true,
                status = app.getString(R.string.signing_in),
                statusIsError = false,
            )
            runCatchingCancellable {
                val session = jellyfin.signIn(s.serverUrl, s.username, s.password)
                // Persist only after the server accepted the credentials, so a typo can never
                // overwrite a working configuration.
                settingsRepo.setConnection(s.serverUrl, s.apiKey)
                settingsRepo.setIndexUrl(s.indexUrl)
                settingsRepo.setSession(session.accessToken, session.user.id, session.user.name)
                // A different user means a different item tree, so the old folder scope points at
                // a parent id that may not exist for them — same reset as switching users by hand.
                val userChanged = session.user.id != _state.value.selectedUserId
                if (userChanged) settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_PATH)
                _state.value = _state.value.copy(
                    busy = false,
                    password = "",
                    signedIn = true,
                    username = session.user.name,
                    selectedUserId = session.user.id,
                    selectedUserName = session.user.name,
                    selectedScopeId = if (userChanged) ROOT_SCOPE_ID else _state.value.selectedScopeId,
                    selectedScopePath = if (userChanged) ROOT_SCOPE_PATH else _state.value.selectedScopePath,
                    // The user picker is an API-key-mode affordance; a token identifies its user.
                    users = emptyList(),
                    status = app.getString(R.string.signed_in_as, session.user.name),
                    statusIsError = false,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    password = "",
                    status = app.getString(R.string.sign_in_failed, reason(e)),
                    statusIsError = true,
                )
            }
        }
    }

    /** Drops the token (and the user it identified); the server URL and API key stay put. */
    fun signOut() {
        if (_state.value.busy) return
        viewModelScope.launch {
            settingsRepo.clearSession()
            _state.value = _state.value.copy(
                signedIn = false,
                password = "",
                selectedUserId = "",
                selectedUserName = "",
                status = app.getString(R.string.signed_out),
                statusIsError = false,
            )
        }
    }

    /** Prefill the metadata index URL from the entered server URL. */
    fun fillIndexUrlFromServer() {
        val base = _state.value.serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return
        _state.value = _state.value.copy(indexUrl = "$base/jellyshelf-index.json")
    }

    /**
     * The **API-key** connect path: save server + key, load the server-wide user list, auto-select
     * the saved/first user. Only reachable from the advanced section — a signed-in user has no use
     * for it, since their token already names them.
     */
    fun connect(silent: Boolean = false) {
        val s = _state.value
        // The screen disables buttons via `busy`, but that state is a frame stale — guard here
        // so two taps landing in the same frame can't run concurrent operations.
        if (s.busy) return
        if (s.serverUrl.isBlank() || s.apiKey.isBlank()) {
            _state.value = s.copy(status = app.getString(R.string.enter_server_and_key), statusIsError = true)
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(
                busy = true,
                status = if (silent) s.status else app.getString(R.string.connecting),
                statusIsError = false,
            )
            runCatchingCancellable {
                val users = jellyfin.getUsers(s.serverUrl, s.apiKey)
                // Persist only after the server accepted the credentials, so a typo can never
                // overwrite a previously working configuration.
                settingsRepo.setConnection(s.serverUrl, s.apiKey)
                settingsRepo.setIndexUrl(s.indexUrl)
                // Trimmed to match what setConnection persists, so the next init's lookup hits.
                settingsCache.store(s.serverUrl.trim(), users)
                val current = _state.value
                val selected = users.firstOrNull { it.id == current.selectedUserId } ?: users.firstOrNull()
                // Auto-selecting a *different* user (server changed, or the saved user is gone)
                // means the old folder scope belongs to another item tree — reset it to root,
                // exactly like a manual selectUser(), or the next sync queries the new server
                // with a nonexistent parent id.
                val userChanged = selected != null && selected.id != current.selectedUserId
                _state.value = current.copy(
                    busy = false,
                    users = users,
                    selectedUserId = selected?.id ?: current.selectedUserId,
                    selectedUserName = selected?.name ?: current.selectedUserName,
                    selectedScopeId = if (userChanged) ROOT_SCOPE_ID else current.selectedScopeId,
                    selectedScopePath = if (userChanged) ROOT_SCOPE_PATH else current.selectedScopePath,
                    status = if (users.isEmpty()) app.getString(R.string.connected_no_users)
                    else app.getString(
                        R.string.connected_users,
                        app.resources.getQuantityString(R.plurals.user_count, users.size, users.size),
                    ),
                    statusIsError = false,
                )
                selected?.let {
                    settingsRepo.setUser(it.id, it.name)
                    if (userChanged) settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_PATH)
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    status = app.getString(R.string.connection_failed, reason(e)),
                    statusIsError = true,
                )
            }
        }
    }

    fun selectUser(user: User) {
        _state.value = _state.value.copy(
            selectedUserId = user.id,
            selectedUserName = user.name,
            // A different user has a different tree — reset scope and open the
            // folder browser so its collections are ready to pick from.
            selectedScopeId = ROOT_SCOPE_ID,
            selectedScopePath = ROOT_SCOPE_PATH,
            browserOpen = true,
            breadcrumb = emptyList(),
            childFolders = emptyList(),
        )
        viewModelScope.launch {
            settingsRepo.setUser(user.id, user.name)
            settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_PATH)
        }
        loadChildren()
    }

    // --- Folder browser ---------------------------------------------------

    fun openBrowser() {
        if (_state.value.selectedUserId.isBlank()) return
        _state.value = _state.value.copy(browserOpen = true, breadcrumb = emptyList())
        loadChildren()
    }

    fun closeBrowser() {
        _state.value = _state.value.copy(browserOpen = false)
    }

    /** Drill into a subfolder. */
    fun enterFolder(folder: FolderRef) {
        _state.value = _state.value.copy(breadcrumb = _state.value.breadcrumb + folder)
        loadChildren()
    }

    /** Jump via the breadcrumb; index -1 == root. */
    fun navigateTo(index: Int) {
        val trimmed = if (index < 0) emptyList() else _state.value.breadcrumb.take(index + 1)
        _state.value = _state.value.copy(breadcrumb = trimmed)
        loadChildren()
    }

    /** Set the folder currently being browsed as the sync scope. */
    fun useCurrentFolder() {
        val id = _state.value.currentParentId
        val path = _state.value.currentPath
        _state.value = _state.value.copy(
            selectedScopeId = id,
            selectedScopePath = path,
            browserOpen = false,
        )
        viewModelScope.launch { settingsRepo.setLibrary(id, path) }
    }

    private var loadChildrenJob: Job? = null

    private fun loadChildren() {
        val s = _state.value
        // Cancel any in-flight load: a slower earlier response must not overwrite the list for
        // the folder the user has since navigated to.
        loadChildrenJob?.cancel()
        loadChildrenJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingFolders = true)
            runCatchingCancellable {
                // Browse with the credentials that are actually saved, not with whatever is
                // half-typed in the fields: the folder tree belongs to the connection the app is
                // configured with, and an edit that hasn't been through Connect isn't one. The
                // user and the folder being browsed do come from the screen — selectUser opens
                // the browser before its own persist has necessarily landed.
                val saved = settingsRepo.snapshot()
                val folders = jellyfin
                    .getChildFolders(saved.serverUrl, saved.credential, s.selectedUserId, s.currentParentId)
                    .map { FolderRef(it.id, it.name, it.path) }
                _state.value = _state.value.copy(childFolders = folders, loadingFolders = false)
            }.onFailure { e ->
                // Message first: [reason] reads the still-signed-in state that [clearSessionIfRejected]
                // is about to drop.
                val message = app.getString(R.string.folders_load_failed, reason(e))
                clearSessionIfRejected(e)
                _state.value = _state.value.copy(
                    childFolders = emptyList(),
                    loadingFolders = false,
                    status = message,
                    statusIsError = true,
                )
            }
        }
    }

    // ----------------------------------------------------------------------

    /** Clear all locally cached videos/categories, keeping connection settings. */
    fun resetLocalData() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, status = app.getString(R.string.clearing_local_data), statusIsError = false)
            // Stop sync first: a worker running through the wipe would refill the tables, and
            // the periodic one must not resurrect the data the user just asked us to drop.
            syncScheduler.cancelAll()
            _sync.value = null
            libraryRepo.clearLocalData()
            _state.value = _state.value.copy(
                busy = false,
                status = app.getString(R.string.local_data_cleared),
                statusIsError = false,
                lastSyncAt = 0L,
            )
        }
    }

    /**
     * Hands the sync to WorkManager and (re)creates the periodic one. Deliberately not awaited:
     * a tab switch clears this ViewModel, which used to cancel the sync mid-flight.
     */
    fun syncNow() {
        val indexUrl = _state.value.indexUrl
        viewModelScope.launch {
            // The worker reads the index URL from settings, so persist before enqueueing — and
            // do both even if this ViewModel is cleared in between.
            withContext(NonCancellable) {
                settingsRepo.setIndexUrl(indexUrl)
                syncScheduler.syncNow()
            }
        }
    }
}
