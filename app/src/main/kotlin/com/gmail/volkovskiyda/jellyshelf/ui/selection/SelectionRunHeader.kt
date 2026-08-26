package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.compose.runtime.Composable
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.ui.BulkRunHeader

/**
 * Progress for the multi-selection run, in the same strip under the top bar that "Remove N watched
 * videos" and "Fetch metadata for N missing" report in — deliberately, because it is the same kind
 * of thing and the user has already learned to read it there.
 *
 * Rendered on both video lists off one repository-held run, so walking from the library into a
 * category (or out to a video and back) while fifty videos are being deleted keeps the progress in
 * view instead of appearing to have abandoned it.
 */
@Composable
internal fun SelectionRunHeader(run: SelectionRun?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (run == null) return
    BulkRunHeader(
        state = run.progress,
        runningLabel = run.action.runningLabel,
        doneLabel = run.action.doneLabel,
        onCancel = onCancel,
        onDismiss = onDismiss,
    )
}
