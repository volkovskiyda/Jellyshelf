package com.gmail.volkovskiyda.jellyshelf.ui.library

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.repository.dataStore
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.grantNotificationPermission
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Searching the library in the **real app**: the real Koin graph, the real DataStore, the real Room
 * database and the real bundled demo asset — one user typing into the field, end to end.
 *
 * The layers below are each covered on their own: the ranking host-side over this same dataset
 * ([com.gmail.volkovskiyda.jellyshelf.data.repository.DemoLibrarySearchTest]), the composition on a
 * device ([com.gmail.volkovskiyda.jellyshelf.data.repository.DemoLibrarySearchInstrumentedTest]),
 * the flow assembly against fakes ([LibraryViewModelTest]), and the field's callbacks
 * ([LibraryContentTest]). None of that proves the *wiring*: a debounce that never fires, a query
 * the screen forwards but never re-collects, a count label reading the wrong list. This does, and
 * it is the only test here that fails if the pieces are individually right and not joined up.
 *
 * Demo mode is the whole fixture on purpose. Search never reaches Jellyfin — it is a Room read plus
 * in-memory ranking — so a real server would add nondeterminism and no coverage.
 *
 * The counts in the top bar carry the assertions: "2/60" is exactly "two shown of sixty", which
 * pins the narrowing in one node that the soft keyboard cannot cover.
 */
@RunWith(AndroidJUnit4::class)
class DemoLibrarySearchFlowTest {

    /** Empty rather than an activity rule, so [resetAppState] runs before the first launch. */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Whatever the previous test left behind, gone — see [com.gmail.volkovskiyda.jellyshelf.ui.DemoModeFlowTest]. */
    @Before
    fun resetAppState() {
        // This test seeds a full demo library, which is exactly what makes the library screen ask
        // for POST_NOTIFICATIONS — its dialog would cover the rows and the search field below.
        grantNotificationPermission()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        runBlocking {
            GlobalContext.get().get<LibraryRepository>().clearLocalData()
            context.dataStore.edit { it.clear() }
        }
        clearLibraryFilters()
    }

    /**
     * Also on the way out, not only on the way in: these tests are the only ones that leave a query
     * applied, and the state they leave it in is *in memory* — so a later class that merely expects
     * a full library (`DemoModeFlowTest`) would find it narrowed by this one.
     */
    @After
    fun leaveTheFiltersClean() = clearLibraryFilters()

    /**
     * [LibraryFilterState] is a Koin singleton, so the query and the last emission live as long as
     * the instrumentation *process* — clearing the DataStore above does not touch them, and
     * `lastVideos` is what a recreated ViewModel seeds from, which is what decides whether the
     * library opens pristine. Leaving it dirty made the top-bar count read the previous test's
     * narrowed list.
     */
    private fun clearLibraryFilters() {
        GlobalContext.get().get<LibraryFilterState>().apply {
            query.value = ""
            lastVideos.value = null
        }
    }

    private fun string(resId: Int) = context.getString(resId)

    private fun awaitText(text: String, timeoutMs: Long = AWAIT_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for the top bar's count to settle, which is how this test synchronises on a new list. */
    private fun awaitCount(label: String) {
        composeRule.waitUntil(AWAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seedTheDemoLibrary() {
        awaitText(string(R.string.try_demo))
        composeRule.onNodeWithText(string(R.string.try_demo)).performClick()
        // Every entry in the bundled asset, and the pristine label is the bare total.
        awaitCount("$DEMO_LIBRARY_SIZE")
    }

    @Test
    fun typingAQuery_narrowsTheLibraryToItsMatches() {
        ActivityScenario.launch(MainActivity::class.java).use {
            seedTheDemoLibrary()

            composeRule.onNodeWithText(string(R.string.search)).performTextInput(QUERY)

            // Two of sixty: the debounce fired, the search ran, and the label describes the
            // narrowed list rather than the library behind it.
            awaitCount("2/$DEMO_LIBRARY_SIZE")
            composeRule.onNodeWithText(SHORT_MATCH, substring = true).assertExists()
            composeRule.onNodeWithText(LONGER_MATCH, substring = true).assertExists()
            composeRule.onNodeWithText(NON_MATCH, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun clearingTheQuery_bringsTheWholeLibraryBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            seedTheDemoLibrary()
            composeRule.onNodeWithText(string(R.string.search)).performTextInput(QUERY)
            awaitCount("2/$DEMO_LIBRARY_SIZE")

            composeRule.onNodeWithContentDescription(string(R.string.clear_search)).performClick()

            // Back to pristine — the bare total, not "60/60".
            awaitCount("$DEMO_LIBRARY_SIZE")
            composeRule.onNodeWithText(NON_MATCH, substring = true).assertExists()
        }
    }

    private companion object {
        /** Generous: a seed writes ~60 rows and their categories to a real database. */
        const val AWAIT_TIMEOUT_MS = 20_000L

        /**
         * Facts about the committed demo dataset, guarded host-side by
         * [com.gmail.volkovskiyda.jellyshelf.data.repository.DemoLibrarySearchTest] and
         * `DemoLibraryAssetTest` — so an edit that invalidates them fails at unit-test speed too,
         * with a message about the dataset rather than about a count in a top bar.
         */
        const val DEMO_LIBRARY_SIZE = 60

        const val QUERY = "ferry"
        const val SHORT_MATCH = "Ferry Timetables of the Outer Sound"
        const val LONGER_MATCH = "Night Ferry to Kirkwall"

        /**
         * Alphabetically first, so it is on screen from the start without scrolling — and it does
         * not match [QUERY], which makes its disappearance the proof that the query was applied.
         */
        const val NON_MATCH = "A Field Guide to Solder Joints"
    }
}
