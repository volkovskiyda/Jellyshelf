package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The theme switch's tap sequence. It is a ping-pong rather than a loop — light → auto → dark →
 * auto → light — so auto is reached from both sides and neither end wraps around, which is the one
 * thing about the control a reader can't infer from the enum's declaration order.
 */
class ThemeStateTest {

    /** Walks [count] taps from [start], collecting the mode after each one. */
    private fun walk(start: ThemeState, count: Int): List<ThemeMode> {
        var state = start
        return List(count) {
            state = state.next()
            state.mode
        }
    }

    @Test
    fun `taps ping-pong from light through auto to dark and back`() {
        assertEquals(
            listOf(ThemeMode.AUTO, ThemeMode.DARK, ThemeMode.AUTO, ThemeMode.LIGHT, ThemeMode.AUTO),
            walk(ThemeState(ThemeMode.LIGHT, towardDark = true), count = 5),
        )
    }

    /** A fresh install sits at auto and heads for dark first, matching the sequence's reading order. */
    @Test
    fun `first tap on a fresh install goes to dark`() {
        assertEquals(ThemeState(ThemeMode.DARK, towardDark = false), ThemeState().next())
    }

    /** The ends bounce whichever way the state claims to have been heading. */
    @Test
    fun `both ends turn around regardless of the stored direction`() {
        assertEquals(
            ThemeState(ThemeMode.AUTO, towardDark = true),
            ThemeState(ThemeMode.LIGHT, towardDark = false).next(),
        )
        assertEquals(
            ThemeState(ThemeMode.AUTO, towardDark = false),
            ThemeState(ThemeMode.DARK, towardDark = true).next(),
        )
    }

    /** Direction is what a restart at auto restores; without it the sequence would jump. */
    @Test
    fun `auto continues in the direction it was left in`() {
        assertEquals(
            ThemeMode.LIGHT,
            ThemeState(ThemeMode.AUTO, towardDark = false).next().mode,
        )
        assertEquals(
            ThemeMode.DARK,
            ThemeState(ThemeMode.AUTO, towardDark = true).next().mode,
        )
    }

    @Test
    fun `every mode round-trips through its stored value`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorage(mode.storageValue))
        }
    }

    /** A missing key (fresh install) or a value from a future build must not break the read. */
    @Test
    fun `absent and unrecognized stored values fall back to auto`() {
        assertEquals(ThemeMode.AUTO, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.AUTO, ThemeMode.fromStorage("garbage"))
        assertEquals(ThemeMode.AUTO, ThemeMode.fromStorage(""))
    }
}
