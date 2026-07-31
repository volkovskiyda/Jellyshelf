package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MainViewModel(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // Tolerate stacks written by an older schema (e.g. a renamed key field) by dropping them
    // rather than crashing the launch; decode failures fall back to a plain Library start.
    // coerceInputValues keeps that fallback from being needed for the one schema change that has
    // already happened: stacks written while AppNavKey.origin was nullable hold an explicit
    // `"origin": null`, which now reads back as PlayerOrigin.None instead of failing the decode
    // and losing the screen the user left on.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val stackSerializer = ListSerializer(AppNavKey.serializer())

    /** The back stack to seed the nav with on launch; null until it's resolved from settings. */
    private val _startStack = MutableStateFlow<List<AppNavKey>?>(null)
    val startStack: StateFlow<List<AppNavKey>?> = _startStack.asStateFlow()

    /**
     * The persisted theme override; null until the first DataStore read lands. Started eagerly
     * rather than on subscription: the activity reads it to decide the very first frame's theme
     * and the system-bar styling, both of which happen before any UI subscribes.
     */
    val themeState: StateFlow<ThemeState?> =
        settingsRepo.themeState.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * A youtubeId whose player screen should open, set by a media-notification tap (the activity
     * forwards the intent extra) and consumed by the nav once pushed. Survives the cold-start
     * gap: the nav collects it only after [startStack] resolves, so a tap that launches the
     * process still lands on the player.
     */
    private val _openPlayer = MutableStateFlow<String?>(null)
    val openPlayer: StateFlow<String?> = _openPlayer.asStateFlow()

    fun requestOpenPlayer(youtubeId: String) {
        _openPlayer.value = youtubeId
    }

    fun consumeOpenPlayer() {
        _openPlayer.value = null
    }

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
            // The player is deliberately not persisted: a relaunch lands on the screen beneath
            // it instead of auto-reopening a player whose playback ended with the process.
            val persistable = stack.filterNot { it is AppNavKey.Player }
            val raw = try {
                json.encodeToString(stackSerializer, persistable)
            } catch (_: Exception) {
                return@launch
            }
            settingsRepo.setBackStackJson(raw)
        }
    }
}
