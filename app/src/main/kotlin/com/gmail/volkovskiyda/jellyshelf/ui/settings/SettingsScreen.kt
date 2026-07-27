package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.User
import org.koin.androidx.compose.koinViewModel

/**
 * Every action the settings UI can trigger, bundled into one parameter so [SettingsContent] keeps
 * a readable signature. The no-op defaults are what let a preview construct `SettingsActions()`
 * and render the screen without a ViewModel.
 */
internal data class SettingsActions(
    val onServerUrlChange: (String) -> Unit = {},
    val onApiKeyChange: (String) -> Unit = {},
    val onIndexUrlChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val fillIndexUrlFromServer: () -> Unit = {},
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
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

/**
 * Settings tab: binds [SettingsViewModel] to the stateless [SettingsContent] below, which
 * previews and tests can render without a ViewModel or a Koin container.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videoCount by viewModel.videoCount.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        videoCount = videoCount,
        actions = SettingsActions(
            onServerUrlChange = viewModel::onServerUrlChange,
            onApiKeyChange = viewModel::onApiKeyChange,
            onIndexUrlChange = viewModel::onIndexUrlChange,
            onUsernameChange = viewModel::onUsernameChange,
            onPasswordChange = viewModel::onPasswordChange,
            fillIndexUrlFromServer = viewModel::fillIndexUrlFromServer,
            signIn = viewModel::signIn,
            signOut = viewModel::signOut,
            connect = { viewModel.connect() },
            selectUser = viewModel::selectUser,
            openBrowser = viewModel::openBrowser,
            closeBrowser = viewModel::closeBrowser,
            enterFolder = viewModel::enterFolder,
            navigateTo = viewModel::navigateTo,
            useCurrentFolder = viewModel::useCurrentFolder,
            syncNow = viewModel::syncNow,
            resetLocalData = viewModel::resetLocalData,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    videoCount: Int,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.jellyfin_connection), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = actions.onServerUrlChange,
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text(stringResource(R.string.server_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // The default auth path: a user-scoped token, so nothing the app holds or hands to an
            // external player is a full-server credential.
            OutlinedTextField(
                value = state.username,
                onValueChange = actions.onUsernameChange,
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                enabled = !state.signedIn,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.signedIn) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = actions.onPasswordChange,
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { actions.signIn() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = if (state.signedIn) actions.signOut else actions.signIn,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.signedIn) R.string.sign_out else R.string.sign_in))
            }

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = actions.onApiKeyChange,
                label = { Text(stringResource(R.string.api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.indexUrl,
                onValueChange = actions.onIndexUrlChange,
                label = { Text(stringResource(R.string.index_url_label)) },
                placeholder = { Text(stringResource(R.string.index_url_hint)) },
                singleLine = true,
                trailingIcon = if (state.indexUrl.isBlank()) {
                    {
                        TextButton(
                            onClick = actions.fillIndexUrlFromServer,
                            enabled = state.serverUrl.isNotBlank(),
                        ) { Text(stringResource(R.string.fill)) }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = actions.connect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.connect_load_users)) }

            if (state.users.isNotEmpty()) {
                Text(stringResource(R.string.user), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.users.forEach { user ->
                        FilterChip(
                            selected = user.id == state.selectedUserId,
                            onClick = { actions.selectUser(user) },
                            label = { Text(user.name) },
                        )
                    }
                }
            }

            if (state.selectedUserId.isNotBlank()) {
                ScopeSection(state = state, actions = actions)
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = actions.syncNow,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sync_now)) }

            OutlinedButton(
                onClick = { showResetDialog = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.reset_local_data)) }

            if (state.busy) CircularProgressIndicator()

            state.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                stringResource(
                    R.string.library_summary,
                    pluralStringResource(R.plurals.video_count, videoCount, videoCount),
                ) + "  •  " +
                    stringResource(if (state.lastSyncAt > 0) R.string.last_sync_recorded else R.string.never_synced),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_dialog_title)) },
            text = {
                Text(stringResource(R.string.reset_dialog_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        actions.resetLocalData()
                    },
                ) {
                    Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScopeSection(state: SettingsUiState, actions: SettingsActions) {
    Text(stringResource(R.string.sync_scope), style = MaterialTheme.typography.titleSmall)
    Text(
        // A blank persisted path is the root scope; the label is resolved here so it follows
        // the device language instead of freezing in the language it was saved in.
        state.selectedScopePath.ifBlank { stringResource(R.string.all_collections) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    if (!state.browserOpen) {
        TextButton(onClick = actions.openBrowser) { Text(stringResource(R.string.change_folder)) }
        return
    }

    // Breadcrumb: All collections › folder › subfolder …
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(onClick = { actions.navigateTo(-1) }, label = { Text(stringResource(R.string.all_collections)) })
        state.breadcrumb.forEachIndexed { index, folder ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            AssistChip(onClick = { actions.navigateTo(index) }, label = { Text(folder.name) })
        }
    }

    // Action row kept ABOVE the folder list so it stays reachable when a folder
    // has many children (the whole screen scrolls; the list can be very long).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.useCurrentFolder) { Text(stringResource(R.string.use_this_folder)) }
        TextButton(onClick = actions.closeBrowser) { Text(stringResource(R.string.cancel)) }
    }

    when {
        state.loadingFolders -> CircularProgressIndicator()
        state.childFolders.isEmpty() ->
            Text(
                stringResource(R.string.no_subfolders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> state.childFolders.forEach { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.enterFolder(folder) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(folder.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.open))
            }
        }
    }
}
