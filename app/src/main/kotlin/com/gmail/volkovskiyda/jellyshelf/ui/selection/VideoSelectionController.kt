package com.gmail.volkovskiyda.jellyshelf.ui.selection

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
@Suppress("TooManyFunctions") // one facade for everything selection mode does; see the split below
class VideoSelectionController(
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
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
    fun exit() {
        selection.exit()
        // The prompt belongs to the selection it was opened over; leaving takes it with it.
        if (!_creatingPlaylist.value) _playlistDialogOpen.value = false
    }

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

    // --- Create playlist ---
    //
    // Not one of the [SelectionAction]s, because it is not the same shape of thing: it needs a name
    // before it can start, it is one server call rather than a walk over the selection, and there
    // is nothing to report progress for. It lives here rather than in either ViewModel so both
    // lists get the same behaviour from one place.

    private val _playlistDialogOpen = MutableStateFlow(false)

    /** Whether the name prompt is up. Closed by a finished creation, not by the screen. */
    val playlistDialogOpen: StateFlow<Boolean> = _playlistDialogOpen.asStateFlow()

    private val _creatingPlaylist = MutableStateFlow(false)
    val creatingPlaylist: StateFlow<Boolean> = _creatingPlaylist.asStateFlow()

    /**
     * One-shot outcome of a playlist creation; consume after showing.
     *
     * The result rather than a finished sentence, so the words stay in the UI layer where the
     * resources are. It is also what keeps this class free of Android, and testable without it.
     */
    private val _playlistResult = MutableStateFlow<PlaylistResult?>(null)
    val playlistResult: StateFlow<PlaylistResult?> = _playlistResult.asStateFlow()

    fun openPlaylistDialog() {
        if (selected.value.isNotEmpty()) _playlistDialogOpen.value = true
    }

    /** Ignored mid-creation: the call is already out, and closing would strand its result. */
    fun dismissPlaylistDialog() {
        if (!_creatingPlaylist.value) _playlistDialogOpen.value = false
    }

    fun consumePlaylistResult() {
        _playlistResult.value = null
    }

    /**
     * Creates the playlist from the current selection. The repository call runs non-cancellable so
     * neither rotation nor leaving the screen aborts it mid-flight; the creating flag is cleared
     * afterwards so the dialog can never stick on "Creating…".
     *
     * The selection survives it. Nothing about those videos changed — a playlist is a new thing
     * built beside them — and the set that was worth one list is often worth a second.
     */
    fun createPlaylist(name: String) {
        if (_creatingPlaylist.value) return
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        _creatingPlaylist.value = true
        scope.launch {
            runCatchingCancellable {
                _playlistResult.value = withContext(NonCancellable) {
                    repo.createPlaylistFromVideos(ids, name)
                }
            }.onFailure { e ->
                _playlistResult.value = PlaylistResult.Error(e.message ?: e.javaClass.simpleName)
            }
            _creatingPlaylist.value = false
            _playlistDialogOpen.value = false
        }
    }
}
