package com.gmail.volkovskiyda.jellyshelf.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackTrail] is what a stall report carries, so what it keeps and what it drops decides whether
 * the report can be read. The two failures worth pinning: keeping the oldest lines instead of the
 * newest (the stall itself falls off the end), and a snapshot that keeps changing after it was taken
 * (the report describes a moment later than the one it was made at).
 */
class PlaybackTrailTest {

    private var now = 0L
    private val trail = PlaybackTrail(capacity = 3) { now }

    private fun messages() = trail.snapshot().map { it.substringAfter(' ') }

    @Test
    fun keepsTheNewestLinesOldestFirst() {
        trail.d("one")
        trail.i("two")
        trail.w("three")
        trail.d("four")

        assertEquals(listOf("two", "three", "four"), messages())
    }

    @Test
    fun stampsEveryLineWithTheWallClock() {
        now = 1_761_572_940_123L
        trail.d("x")

        val line = trail.snapshot().single()
        assertTrue(line, line.matches(Regex("""\d{2}:\d{2}:\d{2}\.123 x""")))
    }

    @Test
    fun aWarningCarriesItsCause() {
        trail.w(IllegalStateException("boom"), "load failed")

        assertEquals(listOf("load failed: java.lang.IllegalStateException: boom"), messages())
    }

    @Test
    fun aSnapshotIsACopy() {
        trail.d("before")
        val snapshot = trail.snapshot()
        trail.d("after")

        assertEquals(1, snapshot.size)
        assertEquals(2, trail.snapshot().size)
    }
}
