package com.gmail.volkovskiyda.jellyshelf.ui.library

import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.ui.FakeLibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The library's flow assembly: a debounced query combined with an undebounced duration filter,
 * routed to one of two repository calls, and tagged with the terms that produced it.
 *
 * Each of those is a decision that can silently invert. Routing a blank query to the search path
 * would work — and quietly cost every browse the full-row read that search needs. Debouncing the
 * duration chip would make a deliberate single tap feel broken. And tagging an emission with the
 * *live* terms rather than the ones behind it is what the `LibraryVideos` KDoc explains at length,
 * because it is what makes the scroll restore fire against the wrong contents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun video(id: String) = Video(
        youtubeId = id,
        jellyfinItemId = "item-$id",
        fileName = "$id.mp4",
        title = "Title $id",
        channel = "Channel",
        channelId = null,
        durationSeconds = 754,
        uploadDate = "20260721",
        description = null,
        tags = emptyList(),
        youtubeCategories = emptyList(),
        thumbnailUrl = null,
        played = false,
        playbackPositionTicks = 0L,
        playCount = 0,
        lastSyncedAt = 0L,
        metadataSource = METADATA_SOURCE_INDEX,
        metadataUpdatedAt = 0L,
        missedSyncs = 0,
    )

    private val browsed = listOf(video("a"), video("b"))
    private val matched = listOf(video("b"))

    private fun filters() = LibraryFilterState(FakeSettingsRepository(), TestDispatcherProvider())

    private fun viewModel(
        repo: FakeLibraryRepository = FakeLibraryRepository(browsed),
        filters: LibraryFilterState = filters(),
    ) = LibraryViewModel(repo, filters)

    /**
     * A ViewModel with the screen's subscription standing in.
     *
     * `videos` shares `WhileUiSubscribed`, so with no collector the whole assembly — debounce,
     * combine, repository call — never runs and every assertion below would read a null that
     * proves nothing.
     */
    private fun TestScope.collectingViewModel(
        repo: FakeLibraryRepository = FakeLibraryRepository(browsed),
        filters: LibraryFilterState = filters(),
    ): LibraryViewModel {
        val vm = viewModel(repo, filters)
        backgroundScope.launch { vm.videos.collect { } }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a blank query takes the browse path, never search`() = runTest {
        val repo = FakeLibraryRepository(browsed)
        val vm = viewModel(repo)

        val emission = vm.videos.first { it != null }

        assertEquals(browsed, emission?.items)
        // The browse path is the cheap one; routing a blank query through search would work and
        // quietly cost every browse a full-row read for a ranking that never runs.
        assertTrue("blank query reached searchVideos", repo.searches.isEmpty())
    }

    @Test
    fun `a non-blank query takes the search path, with the query and filter`() = runTest {
        val repo = FakeLibraryRepository(browsed)
        repo.searchResults.value = matched
        val vm = collectingViewModel(repo)

        vm.onQueryChange("bee")
        advanceUntilIdle()

        assertEquals(matched, vm.videos.value?.items)
        assertEquals(listOf("bee" to null), repo.searches)
    }

    @Test
    fun `a duration filter alone is enough to leave the browse path`() = runTest {
        val repo = FakeLibraryRepository(browsed)
        repo.searchResults.value = matched
        val vm = collectingViewModel(repo)
        repo.searches.clear()

        vm.onDurationFilterChange(DurationBucket.OVER_60)
        advanceUntilIdle()

        assertEquals(listOf("" to DurationBucket.OVER_60), repo.searches)
    }

    @Test
    fun `the query is debounced but the duration chip is not`() = runTest {
        val repo = FakeLibraryRepository(browsed)
        val vm = collectingViewModel(repo)

        // Typing: nothing reaches the repository until the user stops.
        vm.onQueryChange("b")
        vm.onQueryChange("be")
        vm.onQueryChange("bee")
        advanceTimeBy(SHORT_PAUSE_MS)
        assertTrue("a keystroke queried before the debounce elapsed", repo.searches.isEmpty())

        advanceUntilIdle()
        assertEquals("only the settled query should be queried", listOf("bee" to null), repo.searches)

        // A chip tap is one deliberate event, so it applies at once rather than a debounce later.
        repo.searches.clear()
        vm.onDurationFilterChange(DurationBucket.UNDER_10)
        advanceTimeBy(SHORT_PAUSE_MS)
        assertEquals(listOf("bee" to DurationBucket.UNDER_10), repo.searches)
    }

    @Test
    fun `an emission carries the terms that produced it, not the live ones`() = runTest {
        val repo = FakeLibraryRepository(browsed)
        repo.searchResults.value = matched
        val vm = collectingViewModel(repo)

        vm.onQueryChange("bee")
        advanceUntilIdle()
        assertEquals("bee", vm.videos.value?.query)

        // Clearing the query flips the live value immediately; the emission on screen is still the
        // searched one until the browse list arrives, and must still describe itself as such.
        vm.onQueryChange("")
        assertEquals("", vm.query.value)
        assertEquals("bee", vm.videos.value?.query)

        advanceUntilIdle()
        assertEquals("", vm.videos.value?.query)
    }

    @Test
    fun `pristine means no query and no filter`() = runTest {
        val vm = collectingViewModel()
        assertTrue(vm.videos.value?.pristine == true)

        vm.onDurationFilterChange(DurationBucket.OVER_60)
        advanceUntilIdle()
        assertTrue("a filtered list is not pristine", vm.videos.value?.pristine == false)
    }

    @Test
    fun `a recreated ViewModel seeds from the last emission instead of flashing loading`() =
        runTest {
            // What a bottom-nav tab switch does: the ViewModel goes, the singleton holder stays.
            val filters = filters()
            val repo = FakeLibraryRepository(browsed)
            viewModel(repo, filters).videos.first { it != null }

            val recreated = viewModel(FakeLibraryRepository(), filters)

            assertEquals(browsed, recreated.videos.value?.items)
        }

    @Test
    fun `a first-ever ViewModel starts null so the UI can tell loading from empty`() = runTest {
        assertNull(viewModel().videos.value)
    }

    private companion object {
        /** Shorter than the 200 ms search debounce, long enough that an immediate query lands. */
        const val SHORT_PAUSE_MS = 50L
    }
}
