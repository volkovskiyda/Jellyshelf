package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDto
import com.gmail.volkovskiyda.jellyshelf.data.repository.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sentinel for the "all collections" (root) scope. */
const val ROOT_SCOPE_ID = ""
const val ROOT_SCOPE_NAME = "All collections"
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
    // Persisted sync scope.
    val selectedScopeId: String = ROOT_SCOPE_ID,
    val selectedScopePath: String = ROOT_SCOPE_NAME,
    // Ephemeral folder-browser state.
    val browserOpen: Boolean = false,
    val breadcrumb: List<FolderRef> = emptyList(),
    val childFolders: List<FolderRef> = emptyList(),
    val loadingFolders: Boolean = false,
    val busy: Boolean = false,
    val status: String? = null,
    val lastSyncAt: Long = 0L,
) {
    /** ParentId of the folder currently being browsed ("" == root). */
    val currentParentId: String get() = breadcrumb.lastOrNull()?.id ?: ROOT_SCOPE_ID

    /** Human-readable path of the folder currently being browsed. */
    val currentPath: String
        get() = if (breadcrumb.isEmpty()) ROOT_SCOPE_NAME
        else ROOT_SCOPE_NAME + CRUMB_SEPARATOR + breadcrumb.joinToString(CRUMB_SEPARATOR) { it.name }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = container.settingsRepository
    private val libraryRepo = container.libraryRepository
    private val jellyfin = container.jellyfinRepository

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val videoCount: StateFlow<Int> = libraryRepo.videoCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            val s = settingsRepo.snapshot()
            _state.value = _state.value.copy(
                serverUrl = s.serverUrl,
                apiKey = s.apiKey,
                indexUrl = s.indexUrl,
                selectedUserId = s.userId,
                selectedUserName = s.userName,
                selectedScopeId = s.libraryId,
                selectedScopePath = s.libraryName.ifBlank { ROOT_SCOPE_NAME },
                lastSyncAt = s.lastSyncAt,
            )
            if (s.hasCredentials) connect(silent = true)
        }
    }

    fun onServerUrlChange(value: String) { _state.value = _state.value.copy(serverUrl = value) }
    fun onApiKeyChange(value: String) { _state.value = _state.value.copy(apiKey = value) }
    fun onIndexUrlChange(value: String) { _state.value = _state.value.copy(indexUrl = value) }

    /** Save server + key, load users, auto-select the saved/first user. */
    fun connect(silent: Boolean = false) {
        val s = _state.value
        if (s.serverUrl.isBlank() || s.apiKey.isBlank()) {
            _state.value = s.copy(status = "Enter server URL and API key first")
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(busy = true, status = if (silent) s.status else "Connecting…")
            settingsRepo.setConnection(s.serverUrl, s.apiKey)
            settingsRepo.setIndexUrl(s.indexUrl)
            try {
                val users = jellyfin.getUsers(s.serverUrl, s.apiKey)
                val current = _state.value
                val selected = users.firstOrNull { it.id == current.selectedUserId } ?: users.firstOrNull()
                _state.value = current.copy(
                    busy = false,
                    users = users,
                    selectedUserId = selected?.id ?: current.selectedUserId,
                    selectedUserName = selected?.name ?: current.selectedUserName,
                    status = if (users.isEmpty()) "Connected, but no users returned" else "Connected — ${users.size} user(s)",
                )
                selected?.let { settingsRepo.setUser(it.id, it.name) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, status = "Connection failed: ${e.message}")
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
            selectedScopePath = ROOT_SCOPE_NAME,
            browserOpen = true,
            breadcrumb = emptyList(),
            childFolders = emptyList(),
        )
        viewModelScope.launch {
            settingsRepo.setUser(user.id, user.name)
            settingsRepo.setLibrary(ROOT_SCOPE_ID, ROOT_SCOPE_NAME)
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

    private fun loadChildren() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingFolders = true)
            val folders = try {
                jellyfin.getChildFolders(s.serverUrl, s.apiKey, s.selectedUserId, s.currentParentId)
                    .map { FolderRef(it.id, it.name ?: it.id, it.path) }
            } catch (e: Exception) {
                emptyList()
            }
            _state.value = _state.value.copy(childFolders = folders, loadingFolders = false)
        }
    }

    // ----------------------------------------------------------------------

    fun syncNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, status = "Syncing…")
            settingsRepo.setIndexUrl(_state.value.indexUrl)
            val result = libraryRepo.sync()
            val message = when (result) {
                is SyncResult.Success ->
                    "Synced ${result.matched} of ${result.itemCount} items into ${result.categories} channels"
                is SyncResult.Error -> result.message
            }
            val s = settingsRepo.snapshot()
            _state.value = _state.value.copy(busy = false, status = message, lastSyncAt = s.lastSyncAt)
        }
    }
}
