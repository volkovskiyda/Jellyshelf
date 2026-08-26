package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction

/**
 * The bar a list wears while it is being multi-selected, in place of its own. Same slots as the
 * bar it replaces — a leading control, a title, trailing actions — so nothing under it moves when
 * the mode comes and goes.
 *
 * Select all and Deselect all are two buttons rather than one that toggles, which is what the
 * screen was asked for and also what makes them safe: a toggle's meaning depends on state the user
 * has to read off the screen first, and getting it wrong here throws away a selection. Each is
 * disabled when it would do nothing, so the pair is self-describing.
 *
 * The four actions sit behind the overflow rather than on the bar. Three of them are recoverable
 * by acting again; the fourth deletes media on the Jellyfin server, and a one-tap target for that
 * next to the "select everything" button is a mistake waiting to be made.
 *
 * [canAct] closes that menu when there is nothing to act on *or* a run is already in flight — the
 * repository takes one selection run at a time, so a second confirmation would otherwise be
 * accepted and then quietly do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selectedCount: Int,
    canSelectAll: Boolean,
    canAct: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAction: (SelectionAction) -> Unit,
    /** Null on a list a playlist cannot be built from — see the menu below. */
    onCreatePlaylist: (() -> Unit)? = null,
) {
    TopAppBar(
        // Tinted so selection mode is legible at a glance rather than only from the bar's contents
        // — the surrounding list looks much the same either way.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        title = { Text(stringResource(R.string.selected_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.exit_selection))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll, enabled = canSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.select_all))
            }
            IconButton(onClick = onDeselectAll, enabled = selectedCount > 0) {
                Icon(Icons.Filled.Deselect, contentDescription = stringResource(R.string.deselect_all))
            }
            SelectionActionsMenu(enabled = canAct, onAction = onAction, onCreatePlaylist = onCreatePlaylist)
        },
    )
}

/**
 * The menu itself, built in three passes rather than one loop over the enum: everything
 * recoverable, then Create playlist, then the destructive one.
 *
 * The order is the point, not the source of it — the destructive row is last because that is the
 * furthest from the thumb that opened the menu, and writing the pass that way means it stays last
 * whatever is added between. Create playlist sits with the recoverable actions because it is one:
 * it builds something new beside the videos and changes none of them.
 */
@Composable
private fun SelectionActionsMenu(
    enabled: Boolean,
    onAction: (SelectionAction) -> Unit,
    onCreatePlaylist: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, enabled = enabled) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.selection_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        for (action in SelectionAction.entries.filterNot { it.destructive }) {
            SelectionMenuItem(action) {
                expanded = false
                onAction(action)
            }
        }
        onCreatePlaylist?.let { create ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.create_playlist)) },
                onClick = {
                    expanded = false
                    create()
                },
            )
        }
        for (action in SelectionAction.entries.filter { it.destructive }) {
            SelectionMenuItem(action) {
                expanded = false
                onAction(action)
            }
        }
    }
}

@Composable
private fun SelectionMenuItem(action: SelectionAction, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(action.menuLabel),
                color = if (action.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        onClick = onClick,
    )
}

/**
 * Confirms one selection action before it runs. Every action gets one — including the three that
 * are recoverable — because a selection is easy to get wrong in a way a single video is not: the
 * count is the only evidence of what is about to happen, and this dialog is where the user reads
 * it back before committing.
 *
 * The demo variant of the removal's text is the same one the watched removal uses: there is no
 * server behind a demo install, and promising a server delete it cannot perform would be a lie
 * about the one action that cannot be taken back.
 */
@Composable
internal fun SelectionActionDialog(
    action: SelectionAction,
    videoCount: Int,
    demoMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val videos = pluralStringResource(R.plurals.video_count, videoCount, videoCount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.dialogTitle)) },
        text = {
            Text(
                stringResource(
                    if (demoMode && action.destructive) {
                        R.string.remove_watched_dialog_text_demo
                    } else {
                        action.dialogText
                    },
                    videos,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(action.confirmLabel),
                    color = if (action.destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
