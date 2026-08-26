package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.ui.LocalSnackbarHostState

/**
 * Reports a Select all / Deselect all, and offers to take it back.
 *
 * A snackbar rather than nothing at all because both buttons change the selection wholesale and
 * out of the user's view — Select all under a scrolled list picks videos that are nowhere on
 * screen, and Deselect all discards a selection that may have taken a while to assemble. The count
 * is the receipt; Undo is the way out.
 *
 * Keyed on [SelectionUndo.id] so two presses of the same button show two snackbars: the values are
 * otherwise identical, and an effect keyed on the data would sit the second one out. A press that
 * lands while the previous snackbar is still up cancels it — a stale offer to undo a selection
 * that has since changed again is worse than no offer.
 */
@Composable
internal fun SelectionUndoSnackbar(undo: SelectionUndo?, onUndo: () -> Unit, onConsumed: (Long) -> Unit) {
    val hostState = LocalSnackbarHostState.current
    // Resolved in composition, not inside the effect: string lookup needs a composable scope.
    val message = when (undo?.kind) {
        SelectionUndo.Kind.SELECT_ALL -> stringResource(R.string.selected_count, undo.count)
        SelectionUndo.Kind.DESELECT_ALL -> stringResource(R.string.selection_cleared)
        null -> null
    }
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(undo?.id) {
        val pending = undo ?: return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = message.orEmpty(),
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onConsumed(pending.id)
    }
}
