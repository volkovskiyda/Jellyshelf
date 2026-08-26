package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.R

/**
 * Names the playlist about to be built from the selection.
 *
 * The odd one out among the selection's prompts: the other four confirm something the user has
 * already fully described by choosing it, while this one is still collecting an input, so it has
 * a live [creating] state and closes on the result rather than on the tap.
 *
 * [defaultName] is a starting point, not a promise — the category a selection was made in, where
 * there is one, since that is usually most of what the playlist is.
 */
@Composable
internal fun CreatePlaylistDialog(
    defaultName: String,
    videoCount: Int,
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_playlist)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.playlist_dialog_summary,
                        pluralStringResource(R.plurals.video_count, videoCount, videoCount),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.padding(top = 12.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.playlist_name)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
            ) { Text(stringResource(if (creating) R.string.creating else R.string.create)) }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
