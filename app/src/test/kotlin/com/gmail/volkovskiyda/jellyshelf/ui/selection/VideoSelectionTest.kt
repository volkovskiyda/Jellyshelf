package com.gmail.volkovskiyda.jellyshelf.ui.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoSelection]'s rules, which are all about what a bulk change to the selection does to the
 * user's existing one — the part a screen cannot show and a reviewer cannot see.
 *
 * The undo offer is the reason this class exists at all: Select all and Deselect all both discard
 * a selection the user assembled by hand, and the only thing standing between that and losing it
 * is that [SelectionUndo.previous] holds the whole prior set rather than "nothing".
 *
 * A plain unit test, with no repository and no Compose: the selection deliberately has neither.
 */
class VideoSelectionTest {

    private val selection = VideoSelection()

    @Test
    fun aLongPress_entersSelectionModeWithThatVideoPicked() {
        selection.start("a")

        assertTrue(selection.active.value)
        assertEquals(setOf("a"), selection.selected.value)
    }

    @Test
    fun theToolbarButton_entersSelectionModeWithNothingPicked() {
        selection.start()

        assertTrue(selection.active.value)
        assertEquals(emptySet<String>(), selection.selected.value)
    }

    @Test
    fun aSecondLongPress_doesNotResetTheSelectionAlreadyMade() {
        selection.start("a")
        selection.toggle("b")

        selection.start("c")

        assertEquals(setOf("a", "b"), selection.selected.value)
    }

    @Test
    fun togglingOutsideSelectionMode_doesNothing() {
        selection.toggle("a")

        assertFalse(selection.active.value)
        assertEquals(emptySet<String>(), selection.selected.value)
    }

    @Test
    fun togglingAPickedVideo_unpicksIt() {
        selection.start("a")
        selection.toggle("b")

        selection.toggle("a")

        assertEquals(setOf("b"), selection.selected.value)
    }

    @Test
    fun selectAll_addsTheShownVideosWithoutDroppingOnesPickedUnderAnotherFilter() {
        selection.start("off-screen")

        selection.selectAll(listOf("a", "b"))

        assertEquals(setOf("off-screen", "a", "b"), selection.selected.value)
    }

    @Test
    fun undoingSelectAll_restoresTheSelectionAsItWas_notAnEmptyOne() {
        selection.start("a")
        selection.toggle("b")
        selection.selectAll(listOf("a", "b", "c", "d"))
        assertEquals(4, selection.selected.value.size)

        selection.undo()

        assertEquals(setOf("a", "b"), selection.selected.value)
        assertTrue(selection.active.value)
    }

    @Test
    fun deselectAll_clearsThePickWithoutLeavingSelectionMode() {
        selection.start("a")

        selection.deselectAll()

        assertEquals(emptySet<String>(), selection.selected.value)
        // Staying is what gives the undo snackbar somewhere to live.
        assertTrue(selection.active.value)
    }

    @Test
    fun undoingDeselectAll_bringsTheSelectionBack() {
        selection.start("a")
        selection.toggle("b")
        selection.deselectAll()

        selection.undo()

        assertEquals(setOf("a", "b"), selection.selected.value)
    }

    @Test
    fun aSelectAllThatChangesNothing_offersNoUndo() {
        selection.start("a")
        selection.selectAll(listOf("a"))

        assertNull(selection.undo.value)
    }

    @Test
    fun aDeselectAllWithNothingPicked_offersNoUndo() {
        selection.start()

        selection.deselectAll()

        assertNull(selection.undo.value)
    }

    @Test
    fun twoPressesOfTheSameButton_produceTwoDistinctOffers() {
        selection.start("a")
        selection.selectAll(listOf("b"))
        val first = checkNotNull(selection.undo.value)
        selection.selectAll(listOf("c"))
        val second = checkNotNull(selection.undo.value)

        // Same kind, same shape — only the id tells the snackbar these are two separate presses.
        assertEquals(first.kind, second.kind)
        assertTrue(second.id > first.id)
    }

    @Test
    fun consumingAnOffer_onlyClearsTheOfferItNames() {
        selection.start("a")
        selection.selectAll(listOf("b"))
        val stale = checkNotNull(selection.undo.value).id
        selection.deselectAll()

        selection.consumeUndo(stale)

        // The stale snackbar timing out must not take the live offer down with it.
        assertEquals(SelectionUndo.Kind.DESELECT_ALL, selection.undo.value?.kind)
    }

    @Test
    fun undoAfterTheOfferIsConsumed_doesNothing() {
        selection.start("a")
        selection.selectAll(listOf("b", "c"))
        selection.consumeUndo(checkNotNull(selection.undo.value).id)

        selection.undo()

        assertEquals(setOf("a", "b", "c"), selection.selected.value)
    }

    @Test
    fun exiting_dropsTheSelectionAndAnyOutstandingUndo() {
        selection.start("a")
        selection.selectAll(listOf("b"))

        selection.exit()

        assertFalse(selection.active.value)
        assertEquals(emptySet<String>(), selection.selected.value)
        assertNull(selection.undo.value)
    }
}
