package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * App-wide settings snapshot for cheap synchronous reads from composition (e.g. appending the api
 * key to thumbnail URLs at display time); [settings] is null until the first DataStore read lands.
 */
class AppSettingsState(
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) {
    val settings: StateFlow<Settings?> =
        settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, null)
}
