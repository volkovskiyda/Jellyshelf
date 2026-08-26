package com.gmail.volkovskiyda.jellyshelf.ui.selection

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.graphics.vector.ImageVector
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction

/**
 * Every string one selection action needs, hung off the action itself the way `RemoveKind`
 * hangs the two category removals' labels off theirs. Four actions × five labels is exactly the kind of table
 * that goes wrong when it is spelled out at each of the three call sites — the menu, the
 * confirmation dialog and the progress header — instead of once here.
 */
internal val SelectionAction.menuLabel: Int
    @StringRes get() = when (this) {
        SelectionAction.MARK_WATCHED -> R.string.mark_watched
        SelectionAction.MARK_UNWATCHED -> R.string.mark_unwatched
        SelectionAction.UPDATE_METADATA -> R.string.update_metadata
        // Names the server delete rather than saying a bare "Remove": it is the one row here that
        // destroys something no re-sync brings back, and the menu is where that has to be legible
        // — the confirmation it opens is already past the point of deciding.
        SelectionAction.REMOVE -> R.string.selection_delete_from_server
    }

/**
 * The glyph beside each row. The watch-state pair is the same tick filled and outlined, which is
 * the app's own vocabulary for that state rather than a new one invented for this menu — it is
 * what a video row already wears to say whether it has been watched, so the menu row and the
 * result it produces look like each other.
 *
 * Decorative: every row is already named by the text beside it, and a description here would have
 * TalkBack read each one twice.
 */
internal val SelectionAction.menuIcon: ImageVector
    get() = when (this) {
        SelectionAction.MARK_WATCHED -> Icons.Filled.CheckCircle
        SelectionAction.MARK_UNWATCHED -> Icons.Outlined.CheckCircle
        SelectionAction.UPDATE_METADATA -> Icons.Filled.CloudDownload
        SelectionAction.REMOVE -> Icons.Filled.DeleteForever
    }

internal val SelectionAction.dialogTitle: Int
    @StringRes get() = when (this) {
        SelectionAction.MARK_WATCHED -> R.string.selection_mark_watched_title
        SelectionAction.MARK_UNWATCHED -> R.string.selection_mark_unwatched_title
        SelectionAction.UPDATE_METADATA -> R.string.selection_update_metadata_title
        SelectionAction.REMOVE -> R.string.selection_remove_title
    }

/** Takes the video count, already pluralized by the caller. */
internal val SelectionAction.dialogText: Int
    @StringRes get() = when (this) {
        SelectionAction.MARK_WATCHED -> R.string.selection_mark_watched_text
        SelectionAction.MARK_UNWATCHED -> R.string.selection_mark_unwatched_text
        SelectionAction.UPDATE_METADATA -> R.string.selection_update_metadata_text
        SelectionAction.REMOVE -> R.string.selection_remove_text
    }

/** The confirm button, which for the destructive action says what it does rather than "OK". */
internal val SelectionAction.confirmLabel: Int
    @StringRes get() = when (this) {
        SelectionAction.MARK_WATCHED, SelectionAction.MARK_UNWATCHED -> R.string.selection_mark
        SelectionAction.UPDATE_METADATA -> R.string.update
        SelectionAction.REMOVE -> R.string.remove
    }

/** Progress text while the run works, as done and total. */
internal val SelectionAction.runningLabel: Int
    @StringRes get() = when (this) {
        SelectionAction.MARK_WATCHED -> R.string.selection_marking_watched_progress
        SelectionAction.MARK_UNWATCHED -> R.string.selection_marking_unwatched_progress
        SelectionAction.UPDATE_METADATA -> R.string.fetching_progress
        SelectionAction.REMOVE -> R.string.removing_progress
    }

/** Summary text once the run is over, as succeeded and total. */
internal val SelectionAction.doneLabel: Int
    @StringRes get() = when (this) {
        SelectionAction.MARK_WATCHED -> R.string.selection_marked_watched_summary
        SelectionAction.MARK_UNWATCHED -> R.string.selection_marked_unwatched_summary
        SelectionAction.UPDATE_METADATA -> R.string.fetched_summary
        SelectionAction.REMOVE -> R.string.removed_summary
    }

/**
 * The one action that destroys something the user cannot get back — it deletes media on the
 * Jellyfin server. It is styled apart in the menu and in its dialog for that reason alone.
 */
internal val SelectionAction.destructive: Boolean
    get() = this == SelectionAction.REMOVE
