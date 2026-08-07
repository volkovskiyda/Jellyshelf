package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R

/**
 * "Why this app wants to post notifications" — shown before Android's own dialog, never instead
 * of it: [onAllow] is what launches the system request.
 *
 * Stateless, like [UpdateDialog], so previews and behavior tests render it without a ViewModel, a
 * Koin container or a real permission state. The policy behind it lives in
 * `com.gmail.volkovskiyda.jellyshelf.domain.NotificationPrompt`.
 *
 * Every way out except "Allow" is a decline, including a tap outside and Back — the same rule the
 * update dialog follows, and for the same reason: a prompt that returned because the user tapped
 * beside it is the nagging the week-long snooze exists to bound. Declining here deliberately never
 * reaches the system dialog, so it cannot spend one of the two denials that lock the permission
 * for good.
 */
@Composable
internal fun NotificationPermissionDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.notification_permission_title)) },
        text = {
            Text(
                stringResource(R.string.notification_permission_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.notification_permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notification_permission_dismiss))
            }
        },
    )
}
