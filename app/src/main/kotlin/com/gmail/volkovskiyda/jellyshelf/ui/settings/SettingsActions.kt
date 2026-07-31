package com.gmail.volkovskiyda.jellyshelf.ui.settings

import com.gmail.volkovskiyda.jellyshelf.domain.model.User

/**
 * Every action the settings UI can trigger, bundled into one parameter so [SettingsContent] keeps
 * a readable signature. The no-op defaults are what let a preview construct `SettingsActions()`
 * and render the screen without a ViewModel.
 */
internal data class SettingsActions(
    val onThemeModeClick: () -> Unit = {},
    val onServerUrlChange: (String) -> Unit = {},
    val onApiKeyChange: (String) -> Unit = {},
    val onIndexUrlChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTokenInQueryChange: (Boolean) -> Unit = {},
    val fillIndexUrlFromServer: () -> Unit = {},
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val tryDemo: () -> Unit = {},
    val connect: () -> Unit = {},
    val selectUser: (User) -> Unit = {},
    val openBrowser: () -> Unit = {},
    val closeBrowser: () -> Unit = {},
    val enterFolder: (FolderRef) -> Unit = {},
    val navigateTo: (Int) -> Unit = {},
    val useCurrentFolder: () -> Unit = {},
    val syncNow: () -> Unit = {},
    val resetLocalData: () -> Unit = {},
)
