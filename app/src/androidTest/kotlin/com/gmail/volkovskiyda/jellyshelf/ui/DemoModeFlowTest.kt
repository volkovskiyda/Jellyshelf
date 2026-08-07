package com.gmail.volkovskiyda.jellyshelf.ui

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.dataStore
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.grantNotificationPermission
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The whole demo-mode journey through the **real app**: the real Koin graph, the real DataStore,
 * the real Room database and the real bundled asset. Items 01-03 each test their own slice against
 * fakes; nothing else joins them up, and the joins are where a feature like this breaks — a seed
 * that works but never navigates, a Reset that clears rows but leaves the flag.
 *
 * It is also, deliberately, the click path a Firebase Test Lab baseline-profile generator will
 * replay (`internal/release-ci-plan/` item 08): launch → **Try demo** → browse → open a video.
 * Keep the selectors here stable — they are visible user-facing text, which is what UiAutomator can
 * see (Compose `testTag`s are invisible to it unless `testTagsAsResourceId` is set on the root).
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class DemoModeFlowTest {

    /**
     * Empty rather than `createAndroidComposeRule<MainActivity>()`: that launches the activity as
     * the rule is applied, which is *before* [resetAppState] can wipe the persisted state the
     * launch reads. Each test launches its own scenario once the slate is clean.
     */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Whatever the previous test left behind, gone: this suite shares one installed app, one
     * DataStore, one database and one WorkManager.
     *
     * The WorkManager half is not incidental. Manual-sync work left enqueued by
     * [com.gmail.volkovskiyda.jellyshelf.data.worker.SyncSchedulerInstrumentedTest] reaches this
     * screen as a running sync, which disables its buttons and takes over its status line — so
     * "Try demo" would be tapped and ignored, and the sign-in error would be replaced by
     * "Syncing…". Both are exactly what a user would see, which is why the fix is to clear the
     * residue rather than to loosen the assertions.
     *
     * Cancelling is not enough on its own, and neither is wiping Room and DataStore. A **finished**
     * sync stays in WorkManager's history, `SettingsViewModel` replays the last one onto the status
     * line, and a real one from
     * [com.gmail.volkovskiyda.jellyshelf.live.LiveUiJourneyTest] then sits there as "Synced 852/862
     * videos…" over the sign-in error this test is waiting for — so the history is pruned too. And
     * [LibraryFilterState] is a process-lifetime singleton holding the library's search query and
     * its last emission, neither of which lives in a database: a query left by an earlier test
     * filters this one's demo library down to "no videos match".
     */
    @Before
    fun resetAppState() {
        // The demo library is populated by design, so the library screen would ask for
        // POST_NOTIFICATIONS mid-journey and put its dialog over the rows this test clicks.
        grantNotificationPermission()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
        GlobalContext.get().get<LibraryFilterState>().apply {
            query.value = ""
            lastVideos.value = null
        }
        runBlocking {
            GlobalContext.get().get<LibraryRepository>().clearLocalData()
            // After clearLocalData, which writes to this same store.
            context.dataStore.edit { it.clear() }
        }
    }

    /**
     * The first row the library shows, read from the bundled asset rather than hard-coded: the
     * browse order is by file name, which the seeder builds from the title. Deriving it keeps this
     * test working when the demo content is edited — which is the point of the content being a
     * plain JSON asset.
     */
    private fun firstDemoTitle(): String = runBlocking {
        GlobalContext.get().get<IndexSource>().demoEntries()
            .mapNotNull { it.title }
            .minOf { it }
    }

    private fun string(resId: Int) = context.getString(resId)

    private fun awaitText(text: String, timeoutMs: Long = AWAIT_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theDemoJourney_seedsBrowsesAndResets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // A fresh install opens on Settings rather than an empty library.
            awaitText(string(R.string.try_demo))

            composeRule.onNodeWithText(string(R.string.try_demo)).performClick()

            // Seeding navigates to a populated Library — 60 rows out of the bundled JSON.
            val firstTitle = firstDemoTitle()
            awaitText(firstTitle)

            // …and into a video's detail screen, which renders its metadata and offers playback.
            composeRule.onAllNodesWithText(firstTitle, substring = true).onFirst().performClick()
            awaitText(string(R.string.play))
            composeRule.onNodeWithText(string(R.string.mark_watched)).assertExists()

            // Back to the library — by the system gesture, since the detail screen hides the
            // bottom bar — then the Categories tab: auto-categories derived by the same code a
            // real sync uses, so this tab is populated exactly as it would be after one.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitText(firstTitle)
            composeRule.onNodeWithText(string(R.string.tab_categories)).performClick()
            awaitText(string(R.string.dim_channels))

            // The documented way out: Reset local data, which drops the rows and the demo flag
            // together — the button that offers the demo is back afterwards.
            composeRule.onNodeWithText(string(R.string.tab_settings)).performClick()
            awaitText(string(R.string.reset_local_data))
            // Scrolled to, not just found: Settings is a scrolling column, and how much of it fits
            // depends on the device's navigation mode — a 3-button bar costs enough height to leave
            // this button below the fold. Off-screen it is still in the semantics tree, so a plain
            // performClick finds the node and injects a touch nobody receives, which then fails as
            // a missing confirm dialog rather than as the unreachable button it is.
            composeRule.onNodeWithText(string(R.string.reset_local_data)).performScrollTo().performClick()
            // The confirm button lives in a dialog window that composes after the tap.
            awaitText(string(R.string.reset_dialog_title))
            composeRule.onNodeWithText(string(R.string.reset)).performClick()

            awaitText(string(R.string.local_data_cleared))
            composeRule.onNodeWithText(string(R.string.try_demo)).assertExists()
            composeRule.onNodeWithText(string(R.string.demo_mode_active)).assertDoesNotExist()
        }
    }

    /**
     * The other way in: the magic credentials, through the real sign-in form — including the
     * failure password, which demonstrates the authentication-error state with no server anywhere.
     */
    @Test
    fun theDemoSignIn_showsTheErrorStateThenLetsTheDemoIn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText(string(R.string.try_demo))

            composeRule.onNodeWithText(string(R.string.server_url)).performTextInput("jellyfin")
            composeRule.onNodeWithText(string(R.string.username)).performTextInput("demo")
            composeRule.onNodeWithText(string(R.string.password)).performTextInput("incorrect")
            // The password field's Done action rather than the button: with the soft keyboard up
            // — which typing raises — a tap aimed at the button lands on the IME window instead,
            // silently doing nothing. This is also the gesture a user actually makes.
            composeRule.onNodeWithText(string(R.string.password)).performImeAction()

            // The real 401 presentation, with nothing to reject the credentials.
            awaitText(string(R.string.invalid_username_or_password))
            // Nothing was seeded, so the app is still on Settings offering the demo.
            composeRule.onNodeWithText(string(R.string.try_demo)).assertExists()

            // Any other password enters the demo the button would have.
            composeRule.onNodeWithText(string(R.string.password)).performTextInput("hunter2")
            composeRule.onNodeWithText(string(R.string.password)).performImeAction()

            awaitText(firstDemoTitle())
        }
    }

    private companion object {
        /** Generous: a seed writes ~60 rows and their categories to a real database. */
        const val AWAIT_TIMEOUT_MS = 20_000L
    }
}
