package com.gmail.volkovskiyda.jellyshelf.ui.selection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A Select all / Deselect all that has happened but can still be taken back.
 *
 * [previous] is the whole selection as it stood before the button was pressed, because that is
 * what Undo has to put back: a user who had picked three videos, pressed Select all to see what
 * that looked like, and changed their mind wants those three, not an empty list. Restoring "none"
 * would be a second destructive act dressed up as an undo.
 *
 * [id] exists so the snackbar shows once per press and again on the next one, including two
 * presses of the same button: the value is otherwise identical, and an effect keyed on it would
 * sit out the repeat.
 */
data class SelectionUndo(
    val id: Long,
    val kind: Kind,
    val previous: Set<String>,
    /** Videos selected *after* the press — what the snackbar reports. */
    val count: Int,
) {
    enum class Kind { SELECT_ALL, DESELECT_ALL }
}

/**
 * Multi-select state for one video list: whether the list is selecting at all, which videos are
 * picked, and the one bulk change a snackbar can still undo.
 *
 * Held by the ViewModel rather than by the composition, so it survives rotation and a trip to a
 * video's detail screen and back. It does **not** survive the ViewModel — switching tabs leaves
 * selection mode, which is the right answer for a mode the user is meant to be in briefly and on
 * purpose.
 *
 * Selected ids are never pruned against the list currently on screen. A search that hides a picked
 * video has not unpicked it, and neither has an action that moved a video out of the filter it was
 * selected in — marking a video watched inside the Unwatched filter is exactly that, and dropping
 * it from the selection would silently undo half of what the user just asked for. Ids whose row
 * has genuinely gone are handled where it matters: a bulk run resolves ids to rows and simply
 * doesn't find them.
 *
 * State lives in [MutableStateFlow]s per this project's convention for shared mutable slots.
 */
class VideoSelection {
    private val _active = MutableStateFlow(false)

    /** Whether the list is in selection mode. Independent of [selected] being empty. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _undo = MutableStateFlow<SelectionUndo?>(null)

    /** The last Select all / Deselect all, until its snackbar has been shown and consumed. */
    val undo: StateFlow<SelectionUndo?> = _undo.asStateFlow()

    private val undoSerial = MutableStateFlow(0L)

    /**
     * Enters selection mode. [youtubeId] is the video the long press landed on — a press that
     * enters the mode has also picked something, or the user is left in a mode with nothing in it.
     * Null is the top bar's own Select button, which enters the mode without picking anything.
     *
     * No-op once already selecting, so a long press on a second row can't reset the first.
     */
    fun start(youtubeId: String? = null) {
        if (_active.value) return
        _active.value = true
        _selected.value = setOfNotNull(youtubeId)
    }

    fun toggle(youtubeId: String) {
        if (!_active.value) return
        _selected.value = _selected.value.let {
            if (youtubeId in it) it - youtubeId else it + youtubeId
        }
    }

    /**
     * Selects every video in [youtubeIds] — the ones on screen, which under a search or a duration
     * filter is not the whole library. The user can only judge what they are about to act on by
     * what is in front of them, and one of these actions deletes media on the server.
     *
     * Videos picked before a filter narrowed the list stay picked: this adds, it does not replace.
     */
    fun selectAll(youtubeIds: Collection<String>) {
        if (!_active.value) return
        recordUndo(SelectionUndo.Kind.SELECT_ALL) { it + youtubeIds }
    }

    /**
     * Clears the selection without leaving selection mode. Staying is what gives Undo somewhere to
     * live: a mode that closed itself on the last video being unpicked would take the snackbar
     * offering to put them back down with it.
     */
    fun deselectAll() {
        if (!_active.value) return
        recordUndo(SelectionUndo.Kind.DESELECT_ALL) { emptySet() }
    }

    /** Puts back the selection [undo] was recorded against, and clears the offer. */
    fun undo() {
        val pending = _undo.value ?: return
        _selected.value = pending.previous
        _undo.value = null
    }

    /** Drops the offer once its snackbar has come and gone unused. */
    fun consumeUndo(id: Long) {
        if (_undo.value?.id == id) _undo.value = null
    }

    /** Leaves selection mode, dropping the selection and any outstanding undo with it. */
    fun exit() {
        _active.value = false
        _selected.value = emptySet()
        _undo.value = null
    }

    private fun recordUndo(kind: SelectionUndo.Kind, change: (Set<String>) -> Set<String>) {
        val previous = _selected.value
        val next = change(previous)
        // Nothing moved — everything was already selected, or nothing was. Offering to undo a
        // press that changed nothing would put a snackbar in front of the list for no reason.
        if (next == previous) return
        _selected.value = next
        undoSerial.value += 1
        _undo.value = SelectionUndo(undoSerial.value, kind, previous, next.size)
    }
}
