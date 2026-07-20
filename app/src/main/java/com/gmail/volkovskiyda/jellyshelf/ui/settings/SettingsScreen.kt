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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videoCount by viewModel.videoCount.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Jellyfin connection", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:8096") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.indexUrl,
                onValueChange = viewModel::onIndexUrlChange,
                label = { Text("Metadata index URL (optional)") },
                placeholder = { Text("http://host/jellyshelf-index.json") },
                singleLine = true,
                trailingIcon = if (state.indexUrl.isBlank()) {
                    {
                        TextButton(
                            onClick = viewModel::fillIndexUrlFromServer,
                            enabled = state.serverUrl.isNotBlank(),
                        ) { Text("Fill") }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { viewModel.connect() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect & load users") }

            if (state.users.isNotEmpty()) {
                Text("User", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.users.forEach { user ->
                        FilterChip(
                            selected = user.id == state.selectedUserId,
                            onClick = { viewModel.selectUser(user) },
                            label = { Text(user.name) },
                        )
                    }
                }
            }

            if (state.selectedUserId.isNotBlank()) {
                ScopeSection(state = state, viewModel = viewModel)
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = viewModel::syncNow,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync now") }

            OutlinedButton(
                onClick = { showResetDialog = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Reset local data") }

            if (state.busy) CircularProgressIndicator()

            state.status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            Text(
                "Library: $videoCount videos" +
                    if (state.lastSyncAt > 0) "  •  last sync recorded" else "  •  never synced",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset local data?") },
            text = {
                Text(
                    "This clears all synced videos and categories on this device. " +
                        "Server URL, API key, user and folder are kept. " +
                        "Tap Sync now afterwards to rebuild."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetLocalData()
                    },
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScopeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Text("Sync scope", style = MaterialTheme.typography.titleSmall)
    Text(
        state.selectedScopePath,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    if (!state.browserOpen) {
        TextButton(onClick = viewModel::openBrowser) { Text("Change folder…") }
        return
    }

    // Breadcrumb: All collections › folder › subfolder …
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(onClick = { viewModel.navigateTo(-1) }, label = { Text("All collections") })
        state.breadcrumb.forEachIndexed { index, folder ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            AssistChip(onClick = { viewModel.navigateTo(index) }, label = { Text(folder.name) })
        }
    }

    // Action row kept ABOVE the folder list so it stays reachable when a folder
    // has many children (the whole screen scrolls; the list can be very long).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::useCurrentFolder) { Text("Use this folder") }
        TextButton(onClick = viewModel::closeBrowser) { Text("Cancel") }
    }

    when {
        state.loadingFolders -> CircularProgressIndicator()
        state.childFolders.isEmpty() ->
            Text(
                "No subfolders here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> state.childFolders.forEach { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.enterFolder(folder) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(folder.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open")
            }
        }
    }
}
