package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.gmail.volkovskiyda.jellyshelf.data.repository.SyncResult
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val users: List<UserDto> = emptyList(),
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

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val settingsRepo = container.settingsRepository
    private val libraryRepo = container.libraryRepository
    private val jellyfin = container.jellyfinRepository

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val videoCount: StateFlow<Int> = libraryRepo.videoCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Set as soon as the user edits any connection field, see [init]. */
    private var fieldsEdited = false

    init {
        viewModelScope.launch {
            val s = settingsRepo.snapshot()
            val cachedUsers = container.settingsCache.usersFor(s.serverUrl)
            val cur = _state.value
            _state.value = cur.copy(
                // This snapshot loads asynchronously — don't clobber text the user managed
                // to type into the fields before it landed.
                serverUrl = if (fieldsEdited) cur.serverUrl else s.serverUrl,
                apiKey = if (fieldsEdited) cur.apiKey else s.apiKey,
                indexUrl = if (fieldsEdited) cur.indexUrl else s.indexUrl,
                users = cachedUsers.orEmpty(),
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
            if (s.hasCredentials && cachedUsers == null && !fieldsEdited) connect(silent = true)
        }
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

    /** Prefill the metadata index URL from the entered server URL. */
    fun fillIndexUrlFromServer() {
        val base = _state.value.serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return
        _state.value = _state.value.copy(indexUrl = "$base/jellyshelf-index.json")
    }

    /** Save server + key, load users, auto-select the saved/first user. */
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
                container.settingsCache.store(s.serverUrl.trim(), users)
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
                    status = app.getString(
                        R.string.connection_failed,
                        e.message ?: app.getString(R.string.unknown_error),
                    ),
                    statusIsError = true,
                )
            }
        }
    }

    fun selectUser(user: UserDto) {
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
                val folders = jellyfin
                    .getChildFolders(s.serverUrl, s.apiKey, s.selectedUserId, s.currentParentId)
                    .map { FolderRef(it.id, it.name ?: it.id, it.path) }
                _state.value = _state.value.copy(childFolders = folders, loadingFolders = false)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    childFolders = emptyList(),
                    loadingFolders = false,
                    status = app.getString(
                        R.string.folders_load_failed,
                        e.message ?: app.getString(R.string.unknown_error),
                    ),
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
            libraryRepo.clearLocalData()
            _state.value = _state.value.copy(
                busy = false,
                status = app.getString(R.string.local_data_cleared),
                statusIsError = false,
                lastSyncAt = 0L,
            )
        }
    }

    fun syncNow() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, status = app.getString(R.string.syncing), statusIsError = false)
            settingsRepo.setIndexUrl(_state.value.indexUrl)
            val result = libraryRepo.sync()
            val message = when (result) {
                is SyncResult.Success ->
                    app.getString(R.string.sync_summary, result.indexed, result.matched, result.categories)
                is SyncResult.Error -> result.message
            }
            val s = settingsRepo.snapshot()
            _state.value = _state.value.copy(
                busy = false,
                status = message,
                statusIsError = result is SyncResult.Error,
                lastSyncAt = s.lastSyncAt,
            )
        }
    }
}
