package com.gmail.volkovskiyda.jellyshelf.live

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.gmail.volkovskiyda.jellyshelf.DisableAutofillRule
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinApi
import com.gmail.volkovskiyda.jellyshelf.data.remote.JellyfinClient
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserDataDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.UserItemDataBody
import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.data.repository.dataStore
import com.gmail.volkovskiyda.jellyshelf.data.worker.SyncScheduler
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.mediaBrowserAuthHeader
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.grantNotificationPermission
import com.gmail.volkovskiyda.jellyshelf.ui.VIDEO_ROW_DETAILS_TAG
import com.gmail.volkovskiyda.jellyshelf.ui.library.LIBRARY_ROW_TAG
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState
import com.gmail.volkovskiyda.jellyshelf.ui.settings.INDEX_URL_FIELD_TAG
import com.gmail.volkovskiyda.jellyshelf.ui.settings.PASSWORD_FIELD_TAG
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SERVER_URL_FIELD_TAG
import com.gmail.volkovskiyda.jellyshelf.ui.settings.USERNAME_FIELD_TAG
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.test.KoinTest
import org.koin.test.inject
import java.util.UUID

/**
 * The one UI test that drives the **real app against a real Jellyfin**: sign in, scope, sync, mark
 * a video watched, play it, and put every one of those back.
 *
 * [LiveEndpointTest] proves the endpoints deserialize; the demo-mode tests prove the screens work
 * over a fixture. Neither proves the two halves meet — that a tap on **Mark as watched** reaches
 * the server as `Played=true`, that a stop partway leaves a resume point where the server can see
 * it. That join is what breaks in practice, and only a real server can show it.
 *
 * **Every write is undone, and the undo is verified.** The item's server-side `UserData` is read
 * before anything touches it and written back in [tearDown], which then asserts the server agrees —
 * a restore that silently failed would be worse than no restore. The local slate is wiped at both
 * ends, so the device is left signed out with no library, as it was found.
 *
 * **Exactly one item is ever written to**: `JELLYFIN_TEST_ITEM_ID` when `.test.env` pins one,
 * otherwise the library's *first* video, which must already be unwatched (the test skips if it is
 * not). That restriction is what makes the undo exact rather than approximate: Jellyfin's
 * mark-unplayed resets `PlayCount` to 0 and clears `LastPlayedDate`, which restores an item that
 * started at zero and *cannot* restore one that started at three. Every other video in the library
 * is read-only here, and the bulk **Remove watched videos** action is deliberately never touched —
 * it deletes media files from the server, and nothing in a test may do that.
 *
 * Opt-in exactly like [LiveEndpointTest] — `.test.env` — with one extra requirement: a
 * `JELLYFIN_SYNC_FOLDER`, since a root-scoped sync would pull a whole server through the app.
 * Missing config or a down server **skips**, never fails.
 *
 * Two things happen outside the UI on purpose. Sync progress is watched off-screen rather than on
 * it, because a sync that runs for minutes keeps a progress indicator spinning and Compose's test
 * synchronisation would block on that animation (and see [runSync] for what a whole-suite run does
 * to WorkManager). And the server is read through its own token, minted here rather than borrowed
 * from the app, so verification and restore hold regardless of what the app's session is doing —
 * including after it has been signed out.
 */
@RunWith(AndroidJUnit4::class)
// A journey is a sequence of named steps; inlining them into the test would bury the click path
// this class exists to document — the same trade BaselineProfileGenerator makes.
@Suppress("TooManyFunctions")
class LiveUiJourneyTest : KoinTest {

    /**
     * The journey types the real `.test.env` password into an autofill-aware form, and a device
     * with Google Password Manager answers the successful sign-in with its full-screen save
     * sheet — see [DisableAutofillRule] for why it is prevented rather than dismissed. Outermost,
     * so the service is off before anything composes and back on however the test ends.
     */
    @get:Rule(order = 0)
    val disableAutofill = DisableAutofillRule()

    /**
     * Empty rather than `createAndroidComposeRule<MainActivity>()`: that launches the activity as
     * the rule is applied, which is *before* [setUp] can sign in and wipe the persisted state the
     * launch reads.
     *
     * [UnconfinedTestDispatcher] rather than the v2 default of `StandardTestDispatcher`. The default
     * queues composition coroutines on the test scheduler, which drains them on the thread running
     * `runTest` — the instrumentation thread, not the main one. This journey opens the player, and
     * media3's `MediaController` rejects every call made off the application thread, so the
     * `listen` helper behind `rememberPlayerState` dies with "called from a wrong thread" the moment
     * its effect is resumed. Unconfined resumes inline on the thread that composed, which is the
     * main thread — the behaviour the deprecated v1 factories had.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule(UnconfinedTestDispatcher())

    private val config by inject<JellyfinTestConfig>()
    private val jellyfinClient by inject<JellyfinClient>()
    private val dataSource by inject<JellyfinDataSource>()
    private val library by inject<LibraryRepository>()
    private val syncScheduler by inject<SyncScheduler>()
    private val libraryFilterState by inject<LibraryFilterState>()
    private val settings by inject<SettingsRepository>()
    private val deviceInfo by inject<DeviceInfo>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The app-independent session used to verify and to restore. Set once the config gates pass. */
    private lateinit var api: JellyfinApi
    private lateinit var userId: String

    /** What the target item's watch state was before the journey touched it. Null until captured. */
    private var original: ServerWatchState? = null

    /** A library video the journey may write to, with the fields both halves of it need. */
    private data class Target(
        val youtubeId: String,
        val itemId: String,
        val title: String,
    )

    /** The server's answer for one item: what to restore, and what the restore is checked against. */
    private data class ServerWatchState(
        val itemId: String,
        val userData: UserDataDto,
        /** From the server's own `RunTimeTicks` — what its resume thresholds apply to. */
        val runtimeSeconds: Long,
    )

    @Before
    fun setUp() {
        loadKoinModules(liveTestModule)
        assumeTrue("no .test.env config — skipping the live UI journey", config.isConfigured)
        assumeTrue(
            "no sync scope — set JELLYFIN_SYNC_FOLDER or JELLYFIN_SYNC_FOLDER_ID. Without one " +
                "this journey would sync a whole server through the app, which is not something a " +
                "test may set off, so it skips instead.",
            config.hasSyncScope,
        )
        assumeTrue(
            "Jellyfin server unreachable — skipping the live UI journey",
            serverReachable(config.serverUrl),
        )

        grantNotificationPermission()
        api = runBlocking {
            // A fixed device id, so a live run shows up as one stable device on the Jellyfin
            // dashboard rather than one per run. Distinct from the endpoint test's, so the two are
            // told apart there, and distinct from the app's, so this session is never the app's.
            val auth = dataSource.authenticate(
                serverUrl = config.serverUrl,
                username = config.username,
                password = config.password,
                authorization = mediaBrowserAuthHeader(deviceInfo, deviceId = "jellyshelf-live-ui-test"),
            )
            userId = auth.user.id
            jellyfinClient.create(config.serverUrl, auth.accessToken)
        }
        resetAppState()
    }

    /**
     * Undo, in the order the server needs it: the played flag through the same endpoint the app
     * uses, then the resume position, since marking unplayed zeroes it. Then the local slate,
     * leaving the device signed out.
     *
     * Runs after a failed test too, which is the point — a journey that fell over halfway has
     * still written to the server.
     */
    @After
    fun tearDown() {
        if (!::api.isInitialized) {
            unloadKoinModules(liveTestModule)
            return
        }
        try {
            restoreServerWatchState()
        } finally {
            resetAppState()
            unloadKoinModules(liveTestModule)
        }
    }

    @Test
    fun theLiveJourney_signsInSyncsTogglesWatchedAndPlaysOneVideo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            signIn()
            pickSyncScope()
            val target = syncAndPickTarget()

            openDetails(target)
            markWatchedThenUnwatched(target)
            playSeekAndStop(target, scenario)
            browseCategories(scenario)
        }
    }

    // --- The steps ----------------------------------------------------------------------------

    /** The real sign-in form, filled from `.test.env`, plus the metadata index the app offers next. */
    private fun signIn() {
        awaitText(string(R.string.sign_in))
        composeRule.onNodeWithTag(SERVER_URL_FIELD_TAG).performTextInput(config.serverUrl)
        composeRule.onNodeWithTag(USERNAME_FIELD_TAG).performTextInput(config.username)
        composeRule.onNodeWithTag(PASSWORD_FIELD_TAG).performTextInput(config.password)
        // The password field's Done action rather than the button: with the soft keyboard up —
        // which typing raises — a tap aimed at the button lands on the IME window instead. It is
        // also the gesture a user actually makes.
        composeRule.onNodeWithTag(PASSWORD_FIELD_TAG).performImeAction()
        awaitText(string(R.string.sign_out), SIGN_IN_TIMEOUT_MS)

        // The index field appears only once signed in, and lives behind the Advanced expander —
        // collapsed on a fresh launch, so it has to be opened the way a user would.
        // JellyfinTestConfig falls back to the <server>/jellyshelf-index.json convention the
        // screen's own Fill button writes, so this is filled the same either way; an index that
        // 404s only costs the sync its metadata.
        composeRule.onNodeWithText(string(R.string.show_advanced)).performScrollTo().performClick()
        composeRule.onNodeWithTag(INDEX_URL_FIELD_TAG).performScrollTo()
            .performTextInput(config.indexUrl)
    }

    /**
     * Scopes the sync, by walking the in-app picker to `JELLYFIN_SYNC_FOLDER` one browse level at a
     * time, the way a user reaches it.
     *
     * With only `JELLYFIN_SYNC_FOLDER_ID` configured there is no path to walk — an id names a
     * folder but says nothing about where it sits — so the scope is written straight to settings
     * instead, and this run covers everything except the picker. Configure the path as well to get
     * the picker back; when both are set, the id is what proves the picker landed on the right
     * folder rather than on some other one with a matching name.
     */
    private fun pickSyncScope() {
        if (config.syncFolder.isBlank()) {
            setScopeById()
            return
        }
        composeRule.onNodeWithText(string(R.string.change_folder)).performScrollTo().performClick()
        for (segment in config.syncFolder.split("/").map { it.trim() }.filter { it.isNotEmpty() }) {
            awaitText(segment)
            // The last match, not the first: once a level has been entered its name is also in the
            // breadcrumb above the list, and the breadcrumb chip browses *back* to it.
            composeRule.onAllNodesWithText(segment).onLast().performScrollTo().performClick()
        }
        composeRule.onNodeWithText(string(R.string.use_this_folder)).performScrollTo().performClick()
        // The browser is replaced by the Change folder… button again once the scope is committed,
        // which is the one signal that does not also match the breadcrumb left on screen.
        awaitText(string(R.string.change_folder))

        if (config.syncFolderId.isBlank()) return
        assertEquals(
            "the picker scoped sync to a different folder than JELLYFIN_SYNC_FOLDER_ID names — " +
                "the two lines in .test.env disagree, or two folders share a name on that path",
            config.syncFolderId,
            runBlocking { settings.snapshot().libraryId },
        )
    }

    /** Writes the scope `JELLYFIN_SYNC_FOLDER_ID` names, for the runs that have no path to walk. */
    private fun setScopeById() {
        runBlocking {
            val folder = api.getItem(userId, config.syncFolderId)
            settings.setLibrary(config.syncFolderId, folder.name.orEmpty())
        }
    }

    /**
     * Syncs the scoped library and picks the single item this journey is allowed to write to.
     *
     * The sync is waited for off-screen rather than on it, and the difference is not cosmetic: the
     * Settings screen spins an indeterminate progress indicator for as long as the sync runs, and
     * Compose's test synchronisation blocks on a running animation before it will dispatch the next
     * click. Waiting on the worker also covers the yt-dlp auto-fill pass, which runs inside the same
     * `sync()` and long outlives the first rows appearing in Room.
     */
    private fun syncAndPickTarget(): Target {
        // Which runs of the manual sync already exist, so the wait below can tell this one from
        // them. WorkManager keeps finished runs under the unique name — including the ones
        // [resetAppState] just cancelled, and whatever SyncSchedulerInstrumentedTest left behind
        // earlier in the same suite — and "all finished" is true of that history the instant it is
        // asked, before this tap has enqueued anything at all.
        val earlier = runBlocking { syncScheduler.manualSyncInfo().first() }.map { it.id }.toSet()
        composeRule.onNodeWithText(string(R.string.sync_now)).performScrollTo().performClick()
        val queued = poll(ENQUEUE_TIMEOUT_MS, { "the Sync now tap enqueued no manual sync" }) {
            syncScheduler.manualSyncInfo().first().filter { it.id !in earlier }
                .takeIf { it.isNotEmpty() }
        }
        runSync(queued.map { it.id }.toSet())

        val videos = runBlocking { library.observeVideos().first() }
        check(videos.isNotEmpty()) {
            "the sync left the library empty. The scope in JELLYFIN_SYNC_FOLDER may hold no " +
                "videos, or the sync may have failed — its error is on the status line under Sync now."
        }

        val pinned = config.testItemId
        val chosen = if (pinned.isBlank()) {
            videos.first()
        } else {
            videos.firstOrNull { it.jellyfinItemId == pinned }
                ?: error("JELLYFIN_TEST_ITEM_ID=$pinned is not in the scope JELLYFIN_SYNC_FOLDER synced")
        }
        val itemId = requireNotNull(chosen.jellyfinItemId) {
            "\"${chosen.title}\" has no Jellyfin item id — it cannot be the live journey's target"
        }
        val state = readServerWatchState(itemId)
        if (pinned.isBlank()) {
            assumeTrue(
                "the library's first video is already watched. This journey writes only to the " +
                    "first video or to a pinned JELLYFIN_TEST_ITEM_ID, and the target has to start " +
                    "unwatched for the undo to be exact — set JELLYFIN_TEST_ITEM_ID to an unwatched " +
                    "item to run it.",
                !state.userData.played,
            )
        }
        // Armed only now, past every reason to skip: [tearDown] writes whatever this holds, and a
        // restore is not free — putting a watched flag *back* goes through the played-items
        // endpoint, which counts a play. Nothing to undo means nothing to write.
        original = state
        return Target(youtubeId = chosen.youtubeId, itemId = itemId, title = chosen.title)
    }

    /**
     * Waits for the sync the tap enqueued — or runs it here when nothing in this process can.
     *
     * `SyncSchedulerInstrumentedTest` initialises WorkManager's **test** double, and that swap is
     * process-wide and permanent: every test after it in the same instrumentation run inherits a
     * WorkManager whose workers only execute when a test driver says so, and whose configuration
     * carries none of this app's Koin worker factory. So in a full-suite run the tap above enqueues
     * a sync that can never execute, and waiting for it would spend the timeout and fail on a
     * problem the app does not have. Detected rather than guessed at (the test driver exists only
     * on the double), and answered by running the same `sync()` the worker would have called.
     *
     * Run on its own — the documented command, and what CI would use — the real worker runs and
     * this is a plain wait.
     */
    private fun runSync(queued: Set<UUID>) {
        val workerCannotRun = WorkManagerTestInitHelper.getTestDriver(context) != null
        if (workerCannotRun) {
            runBlocking { library.sync() }
            return
        }
        poll(SYNC_TIMEOUT_MS, { "the manual sync never finished" }) {
            syncScheduler.manualSyncInfo().first().filter { it.id in queued }
                .takeIf { fresh -> fresh.isNotEmpty() && fresh.all { it.state.isFinished } }
        }
    }

    /** Opens the target's detail screen through the library's own search, as a user would find it. */
    private fun openDetails(target: Target) {
        composeRule.onNodeWithText(string(R.string.tab_library)).performClick()
        awaitTag(LIBRARY_ROW_TAG)
        composeRule.onNodeWithText(string(R.string.search)).performTextInput(target.title)

        // The target's own row, by title rather than by position — searching narrows the list but
        // does not promise a single row. It is the *details* half of the row that is wanted; the
        // thumbnail beside it plays instead. The title is matched either on the row node or on a
        // descendant, since whether the row merges its children is Compose's business, not this
        // test's.
        val row = hasTestTag(VIDEO_ROW_DETAILS_TAG) and
            (hasText(target.title) or hasAnyDescendant(hasText(target.title)))
        composeRule.waitUntil(AWAIT_TIMEOUT_MS) {
            composeRule.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(row).onFirst().performClick()

        awaitText(string(R.string.play))
        composeRule.onNodeWithText(target.title).assertExists()
    }

    /** The manual watched toggle, there and back, checked on the server at both ends. */
    private fun markWatchedThenUnwatched(target: Target) {
        composeRule.onNodeWithText(string(R.string.mark_watched)).performScrollTo().performClick()
        // The label flips off local state; the server write follows it (see PlaystateWriter).
        awaitText(string(R.string.mark_unwatched))
        assertNotNull(
            "the server never marked the video watched after the toggle",
            pollOrNull(SERVER_WRITE_TIMEOUT_MS) { serverUserData(target.itemId).takeIf { it.played } },
        )

        composeRule.onNodeWithText(string(R.string.mark_unwatched)).performScrollTo().performClick()
        awaitText(string(R.string.mark_watched))
        assertNotNull(
            "the server never un-marked the video after the second toggle",
            pollOrNull(SERVER_WRITE_TIMEOUT_MS) { serverUserData(target.itemId).takeIf { !it.played } },
        )
    }

    /**
     * Real playback: stream the target, pause, seek partway in, and leave — the stop that turns
     * into a resume point locally and a session-stop report on the server.
     *
     * The waits here are what make it a playback test rather than a navigation one. The pause
     * *icon* is on screen the moment the player opens, before a single byte has been fetched — an
     * earlier version of this test seeked into that empty player, whose seek bar ignores every
     * value while it has no duration, and stopped at position zero with nothing to show. So the
     * signal is the seek bar itself: enabled once the media is prepared, and reporting a position
     * that has actually moved once the stream is rolling.
     *
     * Pausing before the seek is deliberate too. A playing player hides its controls a few seconds
     * in, and a hidden seek bar is not a node this test can act on; paused, they stay up. It also
     * exercises the paused progress report on the way through.
     */
    private fun playSeekAndStop(target: Target, scenario: ActivityScenario<MainActivity>) {
        composeRule.onNodeWithText(string(R.string.play)).performScrollTo().performClick()
        awaitContentDescription(string(R.string.pause), PLAYBACK_TIMEOUT_MS)

        composeRule.waitUntil(PLAYBACK_TIMEOUT_MS) {
            composeRule.onAllNodes(seekBar and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }

        // Then let it run. Long enough for `PlaybackService`'s position ticker to have opened the
        // Jellyfin session, which is what makes the stop below an *in-app* stop: the tracker mints
        // the session id on its first tick — ten seconds after playback starts, not when the video
        // is chosen. Pausing sooner sends the whole thing down the external-player carrier instead
        // and leaves the session trio (start → progress → stop) untested, which is what an earlier
        // version of this test did without ever saying so.
        playFor(PAST_FIRST_TICK_MS)

        clickPlayerControl(string(R.string.pause))
        awaitContentDescription(string(R.string.play))
        // Paused, the controls stay up and the seek bar is readable — so this is where the stream
        // is checked to have really rolled, rather than the player screen merely having opened.
        runCatching {
            composeRule.waitUntil(AWAIT_TIMEOUT_MS) {
                seekBarFraction() * runtimeSeconds() >= MIN_PLAYED_SECONDS
            }
        }.getOrElse {
            error(
                "the stream advanced only ${(seekBarFraction() * runtimeSeconds()).toInt()}s of " +
                    "${runtimeSeconds()}s while playing — it stalled, or the server refused to " +
                    "stream this item",
            )
        }

        // Far enough in to clear the app's own resume bar — the smaller of a tenth of the video and
        // a minute — at any duration, and well short of the 90% at which the server would call the
        // video watched. Driving the semantics action is what a screen reader's "set progress"
        // does, and it lands the seek in one step instead of a dozen taps on +30 s.
        composeRule.onNode(seekBar)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(SEEK_FRACTION) }
        // Checked before leaving rather than inferred afterwards: a seek that did not land would
        // otherwise surface as a missing resume position, which has half a dozen other causes.
        composeRule.waitUntil(AWAIT_TIMEOUT_MS) { seekBarFraction() >= MIN_SEEKED_FRACTION }

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        poll(LOCAL_WRITE_TIMEOUT_MS, {
            "the stop recorded no resume position locally — the seek may not have landed"
        }) {
            library.observeVideo(target.youtubeId).first()
                ?.takeIf { it.playbackPositionTicks > 0 && !it.played }
        }
        assertServerResumePoint(target)
    }

    /** The player's seek bar, by the action it carries rather than by a tag it does not have. */
    private val seekBar = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    /** The target's length as the *server* reports it — what its resume thresholds apply to. */
    private fun runtimeSeconds(): Long =
        checkNotNull(original) { "the target's server state was never captured" }.runtimeSeconds

    /**
     * Where the seek bar says playback is, as a fraction of the video, or zero while the controls
     * are hidden.
     *
     * Deliberately passive. Revealing the controls means tapping the video surface, and a tap is
     * only safe to make once: this is read from polling loops, and a loop that taps its way to a
     * readable seek bar taps *fast* — which the player reads as a double-tap seek, and which once
     * ran a video to its end mid-test. Callers that need a reading wait for the controls to be up
     * rather than making them so.
     */
    private fun seekBarFraction(): Float {
        val node = composeRule.onAllNodes(seekBar).fetchSemanticsNodes().firstOrNull() ?: return 0f
        return node.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }
            ?.current ?: 0f
    }

    /**
     * Lets the app run for [millis] while the test keeps turning Compose's clock.
     *
     * A plain `delay` here would not do: this test owns the clock, so sleeping on the test thread
     * stops the screen recomposing while ExoPlayer plays on regardless — the player advances and
     * every reading taken afterwards is whatever the UI last managed to poll.
     */
    private fun playFor(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        composeRule.waitUntil(millis + AWAIT_TIMEOUT_MS) { System.currentTimeMillis() >= deadline }
    }

    /**
     * Clicks a player control, bringing the controls back first if they are not on screen.
     *
     * A playing player hides them a few seconds in, and the gap between finding the control and
     * clicking it is exactly where that can land — Compose waits for the app to be idle before it
     * dispatches, and a stream that re-buffers can spend that wait. A tap on the surface toggles
     * them, so it is only ever made when they are already gone.
     */
    private fun clickPlayerControl(description: String) {
        val visible = composeRule.onAllNodesWithContentDescription(description)
            .fetchSemanticsNodes().isNotEmpty()
        if (!visible) {
            composeRule.onRoot().performClick()
            awaitContentDescription(description)
        }
        composeRule.onNodeWithContentDescription(description).performClick()
    }

    /**
     * The server's half of that stop — and the one assertion that depends on the server's own
     * configuration rather than the app's.
     *
     * Jellyfin stores no resume point for an item shorter than `MinResumeDurationSeconds` (five
     * minutes by default), so for a short target the correct expectation is the opposite one: the
     * stored position is left exactly as it was found. Both are asserted rather than one being
     * skipped, and the poll doubles as the settle time that keeps the fire-and-forget stop report
     * from racing [tearDown]'s restore.
     */
    private fun assertServerResumePoint(target: Target) {
        val before = checkNotNull(original) { "the target's server state was never captured" }
        val moved = pollOrNull(SERVER_WRITE_TIMEOUT_MS) {
            serverUserData(target.itemId).takeIf {
                it.playbackPositionTicks != before.userData.playbackPositionTicks
            }
        }
        if (before.runtimeSeconds >= SERVER_MIN_RESUME_SECONDS) {
            assertNotNull("the server stored no resume position for the stop", moved)
        } else {
            assertNull(
                "the server stored a resume position for a video shorter than its " +
                    "MinResumeDurationSeconds — thresholds changed, or the stop was reported as a " +
                    "finished watch",
                moved,
            )
        }
    }

    /**
     * A read-only pass over the Categories tab, rendering real synced data — by way of clearing the
     * search box, which is the last of this journey's writes to put back.
     *
     * The query is state too, and it outlives the test: `LibraryFilterState` is a process-lifetime
     * singleton, so a query left in it reaches the *next* test's library as a filter over a library
     * it knows nothing about. Cleared through the field's own ✕ for the same reason everything else
     * here is undone through the UI. [resetAppState] clears it again for the runs that never reach
     * this line.
     *
     * The Categories pass stops at the dimension list on purpose. **Others → Watched** leads to the
     * bulk *Remove watched videos* action, which deletes the files from the server — Jellyfin has no
     * trash — so no test goes down that path, however carefully.
     */
    private fun browseCategories(scenario: ActivityScenario<MainActivity>) {
        returnToTopLevel(scenario)
        composeRule.onNodeWithContentDescription(string(R.string.clear_search)).performClick()
        awaitText(string(R.string.search))

        composeRule.onNodeWithText(string(R.string.tab_categories)).performClick()
        awaitText(string(R.string.dim_channels))
    }

    // --- Server state -------------------------------------------------------------------------

    private fun readServerWatchState(itemId: String): ServerWatchState = runBlocking {
        val item = api.getItem(userId, itemId)
        ServerWatchState(
            itemId = itemId,
            userData = item.userData ?: UserDataDto(),
            runtimeSeconds = ticksToSeconds(item.runTimeTicks ?: 0L),
        )
    }

    private fun serverUserData(itemId: String): UserDataDto =
        runBlocking { api.getItem(userId, itemId).userData ?: UserDataDto() }

    private fun restoreServerWatchState() {
        val before = original ?: return
        runBlocking {
            // Order matters: marking unplayed clears the position (and PlayCount, and
            // LastPlayedDate), so the position goes back afterwards rather than before.
            api.setPlayed(userId, before.itemId, before.userData.played)
            api.updateUserData(
                userId = userId,
                itemId = before.itemId,
                body = UserItemDataBody(
                    playbackPositionTicks = before.userData.playbackPositionTicks,
                    played = before.userData.played,
                    lastPlayedDate = before.userData.lastPlayedDate,
                ),
            )
        }
        val after = serverUserData(before.itemId)
        assertEquals(
            "the journey's watched flag was left on the server",
            before.userData.played,
            after.played,
        )
        assertEquals(
            "the journey's resume position was left on the server",
            before.userData.playbackPositionTicks,
            after.playbackPositionTicks,
        )
    }

    // --- App state ----------------------------------------------------------------------------

    /**
     * Wipes what this suite shares: one installed app, one DataStore, one database, one
     * WorkManager. Run at both ends, so the journey starts signed out with an empty library and
     * leaves the device that way rather than holding a real session and a real library.
     *
     * [LibraryFilterState] is on the list because it is a Koin `single` and holds neither of the
     * two things a wipe usually reaches: its search query and its cached last emission live for the
     * process, not in Room or DataStore. Left behind, the query filters the *next* test's library
     * down to nothing and the cached rows flash a real server's videos into it.
     *
     * The work history is pruned as well as cancelled, and that is not belt and braces: cancelling
     * leaves a *finished* WorkInfo in place, WorkManager replays the last one for a unique name to
     * whoever asks, and `SettingsViewModel` turns that replay into the Settings status line. A
     * completed real sync would otherwise sit there as "Synced 852/862 videos…" over the *next*
     * test's sign-in error — which is exactly what it did.
     */
    private fun resetAppState() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
        libraryFilterState.query.value = ""
        libraryFilterState.lastVideos.value = null
        runBlocking {
            library.clearLocalData()
            // After clearLocalData, which writes to this same store.
            context.dataStore.edit { it.clear() }
        }
    }

    // --- Waiting ------------------------------------------------------------------------------

    private fun string(resId: Int) = context.getString(resId)

    private fun awaitText(text: String, timeoutMs: Long = AWAIT_TIMEOUT_MS) = composeRule.waitUntil(timeoutMs) {
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitTag(tag: String, timeoutMs: Long = AWAIT_TIMEOUT_MS) = composeRule.waitUntil(timeoutMs) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitContentDescription(
        description: String,
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
    ) = composeRule.waitUntil(timeoutMs) {
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Presses back until a top-level tab is on screen, i.e. out of however many screens the journey
     * is standing on. Bounded, and a no-op when one already is.
     */
    private fun returnToTopLevel(scenario: ActivityScenario<MainActivity>) {
        repeat(MAX_BACK_PRESSES) {
            val onTab = composeRule.onAllNodesWithText(string(R.string.tab_library))
                .fetchSemanticsNodes().isNotEmpty()
            if (onTab) return
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
        }
    }

    /** [read]'s first non-null result, or a failure describing what never happened. */
    private fun <T : Any> poll(timeoutMs: Long, describe: () -> String, read: suspend () -> T?): T =
        pollOrNull(timeoutMs, read) ?: error(describe())

    /** [read]'s first non-null result, or null once [timeoutMs] is up. */
    private fun <T : Any> pollOrNull(timeoutMs: Long, read: suspend () -> T?): T? = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = read()
        while (result == null && System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            result = read()
        }
        result
    }

    private companion object {
        /** Ordinary on-screen waits: a state change already in flight. */
        const val AWAIT_TIMEOUT_MS = 15_000L

        /** A real round trip to AuthenticateByName, not a local check. */
        const val SIGN_IN_TIMEOUT_MS = 30_000L

        /** A real first sync fetches the scoped library, the index and thumbnails over the network. */
        const val SYNC_TIMEOUT_MS = 300_000L

        /** An enqueue is a database write, not a sync — this is short on purpose. */
        const val ENQUEUE_TIMEOUT_MS = 10_000L

        /** A real stream may transcode before the first frame arrives. */
        const val PLAYBACK_TIMEOUT_MS = 60_000L

        /** Fire-and-forget playstate writes: one request, but on the repository's own scope. */
        const val SERVER_WRITE_TIMEOUT_MS = 20_000L

        /** A Room write from the same coroutine as the report that triggered it. */
        const val LOCAL_WRITE_TIMEOUT_MS = 15_000L

        const val POLL_INTERVAL_MS = 500L

        /**
         * Where to seek before stopping. Above a tenth — the app's resume bar at any duration —
         * and far below the 90% at which Jellyfin's default `MaxResumePct` would call the video
         * watched instead of resumable.
         */
        const val SEEK_FRACTION = 0.15f

        /** The seek landed if the bar has moved to about [SEEK_FRACTION] — keyframes are coarse. */
        const val MIN_SEEKED_FRACTION = 0.1f

        /**
         * How long to play before pausing: past `PlaybackService`'s 10-second position tick, which
         * is where `WatchStateTracker` opens the server session. Coupled to that constant by
         * intent rather than by reference — it is private to the app — so a change there wants a
         * change here.
         */
        const val PAST_FIRST_TICK_MS = 13_000L

        /** A stream that advanced less than this while playing never really rolled. */
        const val MIN_PLAYED_SECONDS = 5f

        /**
         * Jellyfin's default `MinResumeDurationSeconds`: it stores no resume point at all for an
         * item shorter than this, whatever position a client reports.
         */
        const val SERVER_MIN_RESUME_SECONDS = 300L

        /** Deep enough for player → detail → library, with room to spare. */
        const val MAX_BACK_PRESSES = 5
    }
}
