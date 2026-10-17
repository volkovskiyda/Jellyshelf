package com.gmail.volkovskiyda.jellyshelf.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the media-button mirror has to come down between playbacks.
 *
 * A flag left up over nothing is not a cosmetic miss: the next headset press starts the playback
 * service in the foreground for a resumption that fails, and the platform kills the app at the
 * start deadline. So the cases that matter are the ones where the library changes under an id
 * that nothing is playing — and the one that must stay quiet, a video that is still there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NothingToResumeTest {

    private val lastPlayedId = MutableStateFlow<String?>("aaaaaaaaaaa")
    private val library = MutableStateFlow(setOf("aaaaaaaaaaa", "bbbbbbbbbbb"))

    private fun videoExists(id: String): Flow<Boolean> = library.map { id in it }

    /** Collects every signal into a counter for the rest of the test. */
    private fun TestScope.signals(): () -> Int {
        var count = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            nothingToResume(lastPlayedId, ::videoExists).collect { count++ }
        }
        return { count }
    }

    @Test
    fun aVideoStillInTheLibraryLeavesTheFlagAlone() = runTest {
        val count = signals()

        library.value = library.value + "ccccccccccc"

        assertEquals(0, count())
    }

    /** Sign out wipes the library while nothing plays — the case the gate was crashing on. */
    @Test
    fun theLastPlayedVideoLeavingTheLibraryLowersTheFlag() = runTest {
        val count = signals()

        library.value = emptySet()

        assertEquals(1, count())
    }

    /** An explicit stop clears the id; also what a fresh install starts with. */
    @Test
    fun noLastPlayedIdLowersTheFlag() = runTest {
        lastPlayedId.value = null
        val count = signals()

        assertEquals(1, count())
    }

    /** A sync that drops some other video says nothing about the one on offer. */
    @Test
    fun anotherVideoLeavingTheLibraryLeavesTheFlagAlone() = runTest {
        val count = signals()

        library.value = setOf("aaaaaaaaaaa")

        assertEquals(0, count())
    }

    /**
     * Moving on to a video that is there re-arms the rule, so the *second* loss is caught too —
     * a rule that fired once per process would let the flag go stale after the first.
     */
    @Test
    fun eachLossIsSignalledAfterPlaybackMovesOn() = runTest {
        val count = signals()

        library.value = setOf("bbbbbbbbbbb")
        lastPlayedId.value = "bbbbbbbbbbb"
        library.value = emptySet()

        assertEquals(2, count())
    }
}
