package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress

/**
 * Header for a bulk run over the current filter: a button that starts it, live progress with a
 * Cancel while it runs, and a summary with a Dismiss once it finishes. The state comes from the
 * repository, so progress survives leaving the screen and coming back.
 *
 * [runningLabel] and [doneLabel] each take done and total, in that order; [idleLabel] is resolved
 * by the caller because its count is pluralized differently per action. [destructive] renders the
 * start button in the error colour — the caller is expected to confirm before acting on it.
 */
@Composable
internal fun BulkActionHeader(
    state: BulkProgress,
    idleLabel: String,
    @StringRes runningLabel: Int,
    @StringRes doneLabel: Int,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    BulkHeaderFrame {
        when (state) {
            is BulkProgress.Running -> BulkRunningRow(state, runningLabel, onCancel)

            is BulkProgress.Done -> BulkStatusRow(
                text = bulkLabel(doneLabel, state.total - state.failed, state.total, state.failed),
                actionLabel = stringResource(R.string.dismiss),
                onAction = onDismiss,
            )

            BulkProgress.Idle -> BulkStartButton(idleLabel, enabled, destructive, onStart)
        }
    }
}

/**
 * The progress half of [BulkActionHeader], for a run that has no start button of its own — the
 * multi-selection runs, which are started from a confirmation dialog rather than from a header
 * that is standing there beforehand. Idle renders nothing: with no button to offer, a header for a
 * run that isn't happening is just an empty strip.
 */
@Composable
internal fun BulkRunHeader(
    state: BulkProgress,
    @StringRes runningLabel: Int,
    @StringRes doneLabel: Int,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    /** The selection tint, while the list is selecting — see [BulkHeaderFrame]. */
    containerColor: Color = Color.Transparent,
) {
    if (state is BulkProgress.Idle) return
    BulkHeaderFrame(containerColor) {
        when (state) {
            is BulkProgress.Running -> BulkRunningRow(state, runningLabel, onCancel)

            is BulkProgress.Done -> BulkStatusRow(
                text = bulkLabel(doneLabel, state.total - state.failed, state.total, state.failed),
                actionLabel = stringResource(R.string.dismiss),
                onAction = onDismiss,
            )

            BulkProgress.Idle -> Unit
        }
    }
}

/**
 * The strip a bulk header occupies, divider included.
 *
 * [containerColor] is how the multi-selection run carries the selection tint through this strip:
 * it sits between the tinted bar and the tinted rows, and left on the plain surface it reads as a
 * gap in the mode rather than a part of it. Transparent for the category's own headers, which
 * appear on a screen that is not selecting. The divider is inside the tinted column so the band is
 * unbroken down to the content below it.
 */
@Composable
private fun BulkHeaderFrame(containerColor: Color = Color.Transparent, content: @Composable () -> Unit) {
    Column(modifier = Modifier.background(containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = { content() },
        )
        HorizontalDivider()
    }
}

@Composable
private fun BulkRunningRow(state: BulkProgress.Running, @StringRes runningLabel: Int, onCancel: () -> Unit) {
    val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    BulkStatusRow(
        text = bulkLabel(runningLabel, state.done, state.total, state.failed),
        actionLabel = stringResource(R.string.cancel),
        onAction = onCancel,
    )
}

/** "N of M" progress or summary text, with the failure count appended once there is one. */
@Composable
private fun bulkLabel(@StringRes label: Int, done: Int, total: Int, failed: Int): String = buildString {
    append(stringResource(label, done, total))
    if (failed > 0) {
        append(" • ")
        append(stringResource(R.string.failed_count, failed))
    }
}

@Composable
private fun BulkStatusRow(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun BulkStartButton(label: String, enabled: Boolean, destructive: Boolean, onStart: () -> Unit) {
    if (destructive) {
        DestructiveButton(label = label, onClick = onStart, enabled = enabled)
    } else {
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label) }
    }
}
