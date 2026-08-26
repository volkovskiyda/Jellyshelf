package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * How many ids one `IN (...)` query may carry. SQLite's own ceiling on bound variables is 999 on
 * the platform versions this app supports; the round number below it leaves room for the query's
 * other parameters and makes the chunking obvious in a stack trace.
 */
private const val ID_CHUNK = 900

/**
 * Runs one action over the videos a user selected, publishing progress as a [SelectionRun].
 *
 * Split out of `DefaultLibraryRepository` rather than written inline there for the same reason
 * [PlaystateWriter] is: that class is the app's single library facade and already sits near
 * detekt's size ceiling. The per-video work still belongs to the repository — this class takes it
 * as [applyTo] — so nothing about *how* a video is marked, fetched or deleted moves out of the one
 * place that knows how to talk to Room, Jellyfin and yt-dlp.
 *
 * One runner, not four: only one selection run can be in flight, which is what lets a single
 * progress header describe it and a single Cancel stop it. A video that fails is counted and the
 * run moves on, exactly as the library-wide runs do — one unreachable video must never strand the
 * rest of a fifty-video selection.
 *
 * @param loadTargets resolves the selected ids to rows, chunked by [runIn]; ids whose row has
 *   since gone (a sync deleted it, an earlier run removed it) simply do not come back, and the
 *   run's total is what was actually found.
 * @param applyTo performs one video's share of [SelectionAction], returning whether it succeeded.
 * @param afterRun cleanup that has to happen even when the run is cancelled part-way — the
 *   category-leftover prune a removal owes.
 */
internal class SelectionBulkRunner(
    scope: CoroutineScope,
    private val loadTargets: suspend (List<String>) -> List<VideoEntity>,
    private val applyTo: suspend (SelectionAction, VideoEntity) -> Boolean,
    private val afterRun: suspend (SelectionAction) -> Unit,
) {
    private val runner = BulkRunner(scope)

    /** Null between runs, and cleared by [cancel] and [acknowledge] — see [run]. */
    private val _action = MutableStateFlow<SelectionAction?>(null)

    /**
     * The live run, or null when there is nothing to show. Combined from the action and
     * [BulkRunner]'s guarded progress rather than written as one value, so a `Running` update that
     * was already in flight when the user pressed Cancel is dropped by the runner's own generation
     * guard instead of resurfacing here.
     *
     * Eagerly started on the repository's scope: the progress has to keep accumulating while no
     * screen is collecting it, which is the whole reason a bulk run lives on the repository.
     */
    val run: StateFlow<SelectionRun?> =
        combine(runner.progress, _action) { progress, action -> action?.let { SelectionRun(it, progress) } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Starts [action] over [youtubeIds]. No-op while a run is already in flight — the action is
     * published from inside the run block, so a start that the runner refused cannot relabel the
     * run that is already going.
     */
    fun start(action: SelectionAction, youtubeIds: List<String>) = runner.start { publish ->
        _action.value = action
        val targets = youtubeIds.chunked(ID_CHUNK).flatMap { loadTargets(it) }
        if (targets.isEmpty()) {
            publish(BulkProgress.Done(0, 0))
            return@start
        }

        var failed = 0
        publish(BulkProgress.Running(0, targets.size, 0))
        try {
            targets.forEachIndexed { i, video ->
                // An unexpected exception counts as a failure like a refused write does: it must
                // not kill the repository's scope or strand the header on a run that has stopped.
                val ok = runCatchingCancellable { applyTo(action, video) }.getOrElse { e ->
                    Timber.w(e, "Selection $action failed for ${video.youtubeId}")
                    false
                }
                if (!ok) failed++
                publish(BulkProgress.Running(i + 1, targets.size, failed))
            }
        } finally {
            // In a `finally` because a cancelled removal has still deleted rows, and what they
            // left behind has to go with them.
            withContext(NonCancellable) { afterRun(action) }
        }
        publish(BulkProgress.Done(targets.size, failed))
    }

    fun cancel() {
        runner.cancel()
        _action.value = null
    }

    /** Clears a finished run once the UI has shown its summary. */
    fun acknowledge() {
        runner.acknowledge()
        if (runner.progress.value is BulkProgress.Idle) _action.value = null
    }
}
