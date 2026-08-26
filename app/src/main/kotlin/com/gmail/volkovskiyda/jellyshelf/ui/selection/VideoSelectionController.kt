package com.gmail.volkovskiyda.jellyshelf.ui.selection

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The selection half of a video-list ViewModel: the [VideoSelection] the screen renders, the
 * repository-held run its actions start, and the one rule that ties the two together.
 *
 * Shared by both lists rather than written twice, so Library and a category cannot drift into
 * behaving differently in a mode that looks identical on each.
 *
 * The selection itself stays a plain object with no repository behind it — that is what lets its
 * rules be tested without one.
 */
class VideoSelectionController(
    private val repo: LibraryRepository,
    scope: CoroutineScope,
) {
    private val selection = VideoSelection()

    val active: StateFlow<Boolean> = selection.active
    val selected: StateFlow<Set<String>> = selection.selected
    val undo: StateFlow<SelectionUndo?> = selection.undo

    /** The run in flight, held by the repository so it outlives this screen. */
    val run: StateFlow<SelectionRun?> = repo.selectionRun

    init {
        // A finished removal leaves selection mode; the other three actions deliberately do not.
        // The difference is what is left to select: a removal's targets are gone from the list, so
        // staying would leave the user in a mode over a selection that no longer exists — while
        // marking fifty videos watched leaves all fifty right where they were, and the next thing
        // the user wants is often another action on the same fifty.
        //
        // Only on Done, not on a cancel: a cancelled removal has deleted some of them and left the
        // rest, and the selection is still the honest description of what was asked for.
        scope.launch {
            repo.selectionRun.collect { run ->
                if (run?.action == SelectionAction.REMOVE && run.progress is BulkProgress.Done) {
                    selection.exit()
                }
            }
        }
    }

    fun start(youtubeId: String? = null) = selection.start(youtubeId)
    fun toggle(youtubeId: String) = selection.toggle(youtubeId)
    fun selectAll(youtubeIds: Collection<String>) = selection.selectAll(youtubeIds)
    fun deselectAll() = selection.deselectAll()
    fun undoBulkChange() = selection.undo()
    fun consumeUndo(id: Long) = selection.consumeUndo(id)
    fun exit() = selection.exit()

    /**
     * Starts [action] over whatever is selected right now. The ids are snapshotted here — the run
     * outlives the selection, and a user who keeps picking rows while fifty videos are being
     * deleted is composing the *next* action, not extending this one.
     */
    fun startAction(action: SelectionAction) {
        val ids = selected.value
        if (ids.isEmpty()) return
        repo.startSelectionAction(action, ids.toList())
    }

    fun cancelRun() = repo.cancelSelectionAction()
    fun acknowledgeRun() = repo.acknowledgeSelectionRun()
}
