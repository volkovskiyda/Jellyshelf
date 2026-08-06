package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo

/**
 * How tall the release notes may grow before they scroll.
 *
 * Capped because GitHub's generated notes can run to dozens of lines, and an uncapped dialog would
 * push its own buttons off the screen — leaving no way to answer it but Back.
 */
private val NOTES_MAX_HEIGHT = 240.dp

/**
 * "A newer build exists" — the same shape whichever channel found it.
 *
 * Stateless: all of it lives in `UpdateChecker`, so previews and tests render it with no ViewModel,
 * the arrangement `SettingsContent` already uses.
 *
 * Every way out except "Update" is a dismissal, including a tap outside and Back. That is
 * deliberate: a dialog that reappeared on the next launch because the user tapped beside it is
 * exactly the nagging the snooze exists to bound.
 */
@Composable
internal fun UpdateDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.update_available_title, info.versionName)) },
        text = if (info.releaseNotes.isBlank()) {
            // No body at all rather than an empty box: a release with no notes is still an update,
            // and a blank panel reads as something that failed to load.
            null
        } else {
            {
                Column(modifier = Modifier.heightIn(max = NOTES_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_dismiss)) }
        },
    )
}
