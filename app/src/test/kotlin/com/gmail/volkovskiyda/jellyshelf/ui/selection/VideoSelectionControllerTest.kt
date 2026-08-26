package com.gmail.volkovskiyda.jellyshelf.ui.selection

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionAction
import com.gmail.volkovskiyda.jellyshelf.domain.model.SelectionRun
import com.gmail.volkovskiyda.jellyshelf.ui.FakeLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule [VideoSelectionController] adds on top of [VideoSelection]: what a finished run
 * does to the selection that started it.
 *
 * A removal's targets are gone from the list, so the mode goes with them; the other three leave
 * every selected video exactly where it was, and the user is often about to run a second action
 * over the same set. Getting that backwards is invisible in a screenshot and obvious in use, which
 * is precisely the kind of thing worth pinning down here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoSelectionControllerTest {

    /**
     * A controller whose run collector is live and synchronous.
     *
     * The dispatcher is [UnconfinedTestDispatcher] rather than the scope's default, so the
     * collector starts on construction and processes each published run before the next assertion:
     * on a standard test dispatcher the collector is background work, which `advanceUntilIdle` no
     * longer runs, and every assertion below would pass by never observing anything. The Job comes
     * from [TestScope.backgroundScope], so the collector still dies with the test.
     */
    private fun TestScope.controllerFor(repo: FakeLibraryRepository) = VideoSelectionController(
        repo,
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    )

    private fun run(action: SelectionAction, progress: BulkProgress) = SelectionRun(action, progress)

    @Test
    fun aFinishedRemoval_leavesSelectionMode() = runTest {
        val repo = FakeLibraryRepository()
        val controller = controllerFor(repo)
        controller.start("a")
        controller.toggle("b")

        repo.selectionRuns.value = run(SelectionAction.REMOVE, BulkProgress.Running(1, 2, 0))
        assertTrue("still running — the mode stays", controller.active.value)

        repo.selectionRuns.value = run(SelectionAction.REMOVE, BulkProgress.Done(2, 0))

        assertFalse(controller.active.value)
        assertEquals(emptySet<String>(), controller.selected.value)
    }

    @Test
    fun aCancelledRemoval_keepsTheSelection() = runTest {
        val repo = FakeLibraryRepository()
        val controller = controllerFor(repo)
        controller.start("a")

        repo.selectionRuns.value = run(SelectionAction.REMOVE, BulkProgress.Running(1, 5, 0))
        // A cancel resets the runner rather than reporting Done: some videos are gone and the rest
        // are still there, so the selection is still the honest description of what was asked for.
        repo.selectionRuns.value = null

        assertTrue(controller.active.value)
        assertEquals(setOf("a"), controller.selected.value)
    }

    @Test
    fun theOtherThreeActions_keepTheSelectionWhenTheyFinish() = runTest {
        for (action in SelectionAction.entries - SelectionAction.REMOVE) {
            val repo = FakeLibraryRepository()
            val controller = controllerFor(repo)
            controller.start("a")
            controller.toggle("b")

            repo.selectionRuns.value = run(action, BulkProgress.Done(2, 0))

            assertTrue("$action left selection mode", controller.active.value)
            assertEquals("$action changed the selection", setOf("a", "b"), controller.selected.value)
        }
    }

    @Test
    fun startingAnAction_handsTheRepositoryTheSelectedIds() = runTest {
        val repo = FakeLibraryRepository()
        val controller = controllerFor(repo)
        controller.start("a")
        controller.toggle("b")

        controller.startAction(SelectionAction.MARK_WATCHED)

        val (action, ids) = repo.selectionStarts.single()
        assertEquals(SelectionAction.MARK_WATCHED, action)
        assertEquals(setOf("a", "b"), ids.toSet())
    }

    @Test
    fun startingAnActionWithNothingSelected_reachesNoRepository() = runTest {
        val repo = FakeLibraryRepository()
        val controller = controllerFor(repo)
        controller.start()

        controller.startAction(SelectionAction.REMOVE)

        assertTrue(repo.selectionStarts.isEmpty())
    }
}
