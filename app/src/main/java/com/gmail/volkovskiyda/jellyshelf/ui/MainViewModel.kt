package com.gmail.volkovskiyda.jellyshelf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = container.settingsRepository

    /** The screen to open on launch; null until it's resolved from settings. */
    private val _startKey = MutableStateFlow<AppNavKey?>(null)
    val startKey: StateFlow<AppNavKey?> = _startKey.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settingsRepo.snapshot()
            // With no credentials and no sync yet, land on Settings instead of an empty Library.
            _startKey.value = if (!s.hasCredentials && s.lastSyncAt == 0L) {
                AppNavKey.Settings
            } else {
                AppNavKey.Library
            }
        }
    }
}
