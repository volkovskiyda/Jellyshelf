package com.gmail.volkovskiyda.jellyshelf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = container.settingsRepository

    // Tolerate stacks written by an older schema (e.g. a renamed key field) by dropping them
    // rather than crashing the launch; decode failures fall back to a plain Library start.
    private val json = Json { ignoreUnknownKeys = true }
    private val stackSerializer = ListSerializer(AppNavKey.serializer())

    /** The back stack to seed the nav with on launch; null until it's resolved from settings. */
    private val _startStack = MutableStateFlow<List<AppNavKey>?>(null)
    val startStack: StateFlow<List<AppNavKey>?> = _startStack.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settingsRepo.snapshot()
            // With no credentials and no sync yet, land on Settings instead of an empty Library.
            _startStack.value = if (!s.hasCredentials && s.lastSyncAt == 0L) {
                listOf(AppNavKey.Settings)
            } else {
                restoreStack() ?: listOf(AppNavKey.Library)
            }
        }
    }

    /**
     * The saved back stack, or null if none/unreadable. Library is the app's home and always the
     * stack root, so a restored stack that doesn't start there gets Library prepended — that keeps
     * Back walking down to Library and then exiting, whatever screen the user left on.
     */
    private suspend fun restoreStack(): List<AppNavKey>? {
        val raw = settingsRepo.backStackJson.first() ?: return null
        val decoded = try {
            json.decodeFromString(stackSerializer, raw)
        } catch (_: Exception) {
            return null
        }
        if (decoded.isEmpty()) return null
        return if (decoded.first() == AppNavKey.Library) decoded else listOf(AppNavKey.Library) + decoded
    }

    /** Persist the current back stack so the next launch reopens on the exact same screen. */
    fun saveBackStack(stack: List<AppNavKey>) {
        viewModelScope.launch {
            val raw = try {
                json.encodeToString(stackSerializer, stack)
            } catch (_: Exception) {
                return@launch
            }
            settingsRepo.setBackStackJson(raw)
        }
    }
}
