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
 * "Why this app wants to reach your local network" — shown before Android's own dialog, never
 * instead of it: [onAllow] is what launches the system request.
 *
 * Stateless, like [NotificationPermissionDialog] beside it, so previews and behavior tests render
 * it without a ViewModel, a Koin container or a real permission state.
 *
 * It exists because this is the one permission whose absence looks like somebody else's fault.
 * Android does not refuse a blocked local-network connection, it drops it, so the app waits out its
 * own request timeout and says the server is unreachable — a message that sends the user to their
 * router rather than to a permission screen. Saying so before the request is the only place the
 * connection can be made.
 *
 * Every way out except "Allow" is a decline, including a tap outside and Back, matching the
 * notification rationale. Declining here never reaches the system dialog, so it cannot spend one of
 * the two denials that lock a permission for good.
 */
@Composable
internal fun LocalNetworkPermissionDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.local_network_permission_title)) },
        text = {
            Text(
                stringResource(R.string.local_network_permission_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.local_network_permission_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.local_network_permission_dismiss))
            }
        },
    )
}
