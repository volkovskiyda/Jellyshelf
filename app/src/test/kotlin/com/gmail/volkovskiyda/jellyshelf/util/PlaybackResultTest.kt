package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five branches of the external-player result parse. A regression here silently loses watch
 * state — the player exits, nothing is recorded, and nothing anywhere reports a failure — so each
 * branch is pinned individually.
 *
 * Exercises [Playback.parsePlayerResult] rather than `parseResult(Intent)`: the Intent/Bundle
 * reads are the only Android-dependent part, and keeping the decision pure keeps these host-side.
 */
class PlaybackResultTest {

    private fun parse(
        endBy: String? = null,
        positionMs: Int? = null,
        vlcPositionMs: Long? = null,
        vlcDurationMs: Long? = null,
    ) = Playback.parsePlayerResult(endBy, positionMs, vlcPositionMs, vlcDurationMs)

    // --- MX Player ---

    /** The regression the implementation comment warns about: completion arrives with no position. */
    @Test
    fun `MX Player completion without a position is still a completion`() {
        assertEquals(Playback.Result(0L, completed = true), parse(endBy = "playback_completion"))
    }

    @Test
    fun `MX Player completion carrying a position keeps it`() {
        assertEquals(
            Playback.Result(754_000L, completed = true),
            parse(endBy = "playback_completion", positionMs = 754_000),
        )
    }

    @Test
    fun `MX Player stop mid-video reports the position and is not a completion`() {
        assertEquals(
            Playback.Result(120_000L, completed = false),
            parse(endBy = "user", positionMs = 120_000),
        )
    }

    /** `end_by` present but no usable position: nothing to record, and not a completion either. */
    @Test
    fun `MX Player result with no usable position is ignored`() {
        assertNull(parse(endBy = "user", positionMs = -1))
        assertNull(parse(endBy = "user"))
    }

    /** A position of exactly 0 is a real answer ("restart from the top"), not a missing one. */
    @Test
    fun `MX Player position of zero is reported rather than dropped`() {
        assertEquals(Playback.Result(0L, completed = false), parse(positionMs = 0))
    }

    // --- VLC ---

    @Test
    fun `VLC near the end counts as completed within the tolerance`() {
        // 5 s tolerance: 1 s short of the end is a completion, 6 s short is not.
        assertEquals(
            Playback.Result(599_000L, completed = true),
            parse(vlcPositionMs = 599_000L, vlcDurationMs = 600_000L),
        )
        assertEquals(
            Playback.Result(594_000L, completed = false),
            parse(vlcPositionMs = 594_000L, vlcDurationMs = 600_000L),
        )
    }

    /** Without a duration there is nothing to compare against, so completion can't be inferred. */
    @Test
    fun `VLC without a duration is never inferred as completed`() {
        assertEquals(
            Playback.Result(599_000L, completed = false),
            parse(vlcPositionMs = 599_000L),
        )
    }

    @Test
    fun `VLC invalid position is ignored`() {
        assertNull(parse(vlcPositionMs = -1L, vlcDurationMs = 600_000L))
    }

    // --- neither, and precedence ---

    @Test
    fun `a player that reported nothing yields no result`() {
        assertNull(parse())
    }

    /** A player sending both shapes: the MX answer wins, and VLC is only the fallback. */
    @Test
    fun `MX Player extras take precedence over VLC extras`() {
        assertEquals(
            Playback.Result(120_000L, completed = false),
            parse(positionMs = 120_000, vlcPositionMs = 599_000L, vlcDurationMs = 600_000L),
        )
    }

    /** MX extras present but unusable must not swallow a perfectly good VLC position. */
    @Test
    fun `an unusable MX Player result falls through to the VLC extras`() {
        assertEquals(
            Playback.Result(300_000L, completed = false),
            parse(endBy = "user", positionMs = -1, vlcPositionMs = 300_000L, vlcDurationMs = 600_000L),
        )
    }
}
