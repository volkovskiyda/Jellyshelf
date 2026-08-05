package com.gmail.volkovskiyda.jellyshelf.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.Duration

/**
 * [DemoBackend] with its two non-deterministic parts pinned: the failure dice and the simulated
 * round-trip time. Everything else — the bundled entries, the error wording, the exception type —
 * stays real, because that is what the tests are there to check.
 *
 * [failCall] receives the 1-based index of each call that rolls the dice and decides whether it
 * fails, so a test can express "all succeed" (the default), "all fail", or a specific mix.
 */
class TestDemoBackend(
    indexSource: IndexSource,
    private val failCall: (call: Int) -> Boolean = { false },
) : DemoBackend(indexSource) {

    private val _calls = MutableStateFlow(0)

    /** How many calls have rolled the dice — i.e. how many operations the backend has served. */
    val calls: Int get() = _calls.value

    override fun failed(): Boolean = failCall(_calls.updateAndGet { it + 1 })

    /** No sleeping: these tests run on real dispatchers, where the real pauses would be real. */
    override suspend fun pause(range: ClosedRange<Duration>) = Unit
}
