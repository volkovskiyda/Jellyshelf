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

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val indexUrl: String = "",
    val users: List<UserDto> = emptyList(),
    val selectedUserId: String = "",
    val selectedUserName: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val lastSyncAt: Long = 0L,
)

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
                lastSyncAt = s.lastSyncAt,
            )
        }
    }

    fun onServerUrlChange(value: String) { _state.value = _state.value.copy(serverUrl = value) }
    fun onApiKeyChange(value: String) { _state.value = _state.value.copy(apiKey = value) }
    fun onIndexUrlChange(value: String) { _state.value = _state.value.copy(indexUrl = value) }

    /** Save server + key, then load the list of users to pick from. */
    fun connect() {
        val s = _state.value
        if (s.serverUrl.isBlank() || s.apiKey.isBlank()) {
            _state.value = s.copy(status = "Enter server URL and API key first")
            return
        }
        viewModelScope.launch {
            _state.value = s.copy(busy = true, status = "Connecting…")
            settingsRepo.setConnection(s.serverUrl, s.apiKey)
            settingsRepo.setIndexUrl(s.indexUrl)
            try {
                val users = jellyfin.getUsers(s.serverUrl, s.apiKey)
                val current = _state.value
                val autoSelected = users.firstOrNull { it.id == current.selectedUserId } ?: users.firstOrNull()
                _state.value = current.copy(
                    busy = false,
                    users = users,
                    selectedUserId = autoSelected?.id ?: current.selectedUserId,
                    selectedUserName = autoSelected?.name ?: current.selectedUserName,
                    status = if (users.isEmpty()) "Connected, but no users returned" else "Connected — ${users.size} user(s)",
                )
                autoSelected?.let { settingsRepo.setUser(it.id, it.name) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, status = "Connection failed: ${e.message}")
            }
        }
    }

    fun selectUser(user: UserDto) {
        _state.value = _state.value.copy(selectedUserId = user.id, selectedUserName = user.name)
        viewModelScope.launch { settingsRepo.setUser(user.id, user.name) }
    }

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
