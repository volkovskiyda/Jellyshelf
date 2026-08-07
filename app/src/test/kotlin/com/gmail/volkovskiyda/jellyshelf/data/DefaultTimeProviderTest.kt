package com.gmail.volkovskiyda.jellyshelf.data

import org.junit.Test
import kotlin.test.assertTrue

/**
 * The one thing worth asserting about a clock: that it is the *wall* clock.
 *
 * `System.nanoTime()` and `SystemClock.elapsedRealtime()` also return a plausible-looking Long, and
 * substituting either would compile, pass every fake-clock test in the suite, and then quietly
 * break every stored timestamp — the sync markers and the three update windows all compare against
 * epoch milliseconds written by earlier installs. Bracketing catches that: an uptime counter cannot
 * land between two `currentTimeMillis` reads.
 */
class DefaultTimeProviderTest {

    @Test
    fun now_isEpochMillis() {
        val before = System.currentTimeMillis()
        val now = DefaultTimeProvider().now()
        val after = System.currentTimeMillis()

        assertTrue(now in before..after, "Expected an epoch instant in [$before, $after], got $now")
    }

    @Test
    fun now_isReadEveryCall() {
        val provider = DefaultTimeProvider()
        val first = provider.now()
        // Busy-wait rather than sleep: this needs the clock to have moved, and Thread.sleep in a
        // unit test is the thing TimeProvider exists to make unnecessary elsewhere.
        while (System.currentTimeMillis() == first) Thread.yield()

        assertTrue(provider.now() > first, "A cached value would make every window permanent")
    }
}
