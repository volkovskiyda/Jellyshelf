package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Writes the baseline profile that ships in the release APK, by driving the real app through the
 * paths a user hits first: launch, the library list and its scroll, a video's detail screen, and
 * playback.
 *
 * Run it by hand with the Pixel 5 attached, awake and unlocked — it is not part of any build:
 *
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 *
 * then commit what lands in `app/src/release/generated/baselineProfiles/` — but check it first:
 * two failure modes yield a plausible-looking, worthless profile, and `docs/BASELINE-PROFILE.md` is
 * the runbook that lists them along with the rest of the prerequisites. Regenerate when the
 * startup path changes shape, not on every commit. CI never runs this: the app APK is arm64-only,
 * which no x86_64 runner or managed device can install, and `automaticGenerationDuringBuild` stays
 * false so `assembleRelease` never needs a device.
 *
 * The journey runs against **demo mode**, not a real Jellyfin server: "Try demo" seeds ~60 videos
 * from a bundled asset and every video plays a bundled clip, so the run needs no network, no
 * account and no server, and two profiles differ because the *app* changed rather than because the
 * library did. It deliberately mirrors `DemoModeFlowTest.theDemoJourney_seedsBrowsesAndResets`,
 * which the instrumented suite runs on every device build — that test is what keeps this click path
 * honest.
 *
 * A third test, [generateSyncJourney], additionally covers what demo mode cannot: the real-server
 * first-use path — sign-in, the first sync, and network image loads. It runs only when `.test.env`
 * is filled (the same file the live-endpoint tests read) and skips otherwise, so serverless
 * generation still produces the two demo-driven profiles above unchanged.
 *
 * The two selectors that carry the run — the list and its rows — are resource ids published by
 * `testTagsAsResourceId` on the root Scaffold, because UiAutomator cannot see Compose test tags
 * otherwise and copy is not a contract. The rest match visible text, on controls with no tag of
 * their own. Every lookup that matters goes through [await], which throws rather than returning
 * null: a selector that quietly stopped matching would otherwise yield a profile covering the
 * launch and nothing else, with nothing to show for it.
 */
@RunWith(AndroidJUnit4::class)
// [generateJourney] must run before [generateStartup] — see the latter. J sorts before S.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * The full journey, and the bulk of the profile. Deliberately **not** in the startup profile:
     * that one is stored in the primary dex to be read on every cold start, so putting a video
     * player and two list screens in it would cost startup rather than buy it.
     */
    @Test
    fun generateJourney() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()

        // The app restores its navigation stack across the process kill between iterations, and
        // the detail and player screens hide the bottom bar — so a run that ended on one of them
        // leaves the next iteration with nothing this generator can steer by. Back out first.
        returnToTopLevel()

        // Only the first iteration lands here: a fresh install opens on Settings, and "Try demo"
        // seeds the library and navigates to it by itself. Afterwards the demo flag lives in
        // DataStore and outlives the process, so the button is simply absent and the tab is how we
        // get to the library.
        val demoEntry = device.wait(Until.findObject(By.text(TRY_DEMO)), TIMEOUT_MS)
        if (demoEntry != null) {
            demoEntry.click()
        } else {
            device.wait(Until.findObject(By.text(TAB_LIBRARY)), TIMEOUT_MS)?.click()
        }

        val list = await(By.res(LIBRARY_LIST), SEED_TIMEOUT_MS) {
            "Library list never appeared. Either the demo seed did not finish, or the resource-id " +
                "bridge is gone — check testTagsAsResourceId on MainActivity's root Scaffold."
        }
        // Keep the gesture off the display edges, which the system back gesture owns.
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
        repeat(SCROLLS) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()

        // A row's text opens the detail screen: image loading and the metadata section.
        openDetails()

        // Playback pulls in Media3/ExoPlayer, a large slice of first-use class loading the launch
        // path alone would miss. In demo mode this is a bundled ten-second clip, so it costs the
        // generation run almost nothing.
        await(By.text(PLAY), TIMEOUT_MS) {
            "The detail screen never offered playback."
        }.click()
        device.waitForIdle()

        // Out of the player and the detail screen — however many steps that takes — then across to
        // Categories, a different Compose surface.
        returnToTopLevel()
        device.wait(Until.findObject(By.text(TAB_CATEGORIES)), TIMEOUT_MS)?.click()
        device.waitForIdle()

        // Finish on the library. The next iteration inherits this as its restored screen, and a
        // top-level tab is the one state it can start from without backing out of anything.
        device.wait(Until.findObject(By.text(TAB_LIBRARY)), TIMEOUT_MS)?.click()
        device.waitForIdle()
    }

    /**
     * Cold launch and nothing else — the part ART's dexopt gap hurts most, and the only part worth
     * carrying in the primary dex.
     *
     * It relies on [generateJourney] having run first, which `@FixMethodOrder` guarantees: that
     * leaves the demo library seeded and the app resting on the Library tab, so this measures a
     * launch into a populated list — what a real user gets — rather than into the first-run
     * Settings screen. Seeding here instead would put the whole seed path into the startup profile.
     */
    @Test
    fun generateStartup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        await(By.res(LIBRARY_LIST), TIMEOUT_MS) {
            "Cold launch did not land on the library. generateJourney must run first — check that " +
                "@FixMethodOrder(NAME_ASCENDING) still puts it before this test."
        }
    }

    /**
     * The real-server first-use path — everything demo mode cannot reach: `AuthenticateByName`,
     * the Ktor/OkHttp/TLS stack, kotlinx.serialization over live responses, sync writing a real
     * library into Room, and Coil fetching thumbnails over the network. Journey profile only,
     * never the startup one, and named to sort *after* [generateStartup] so the two demo-driven
     * profiles above keep their no-server determinism.
     *
     * Opt-in via `.test.env` exactly like the live-endpoint tests ([assumeTrue] skips the test
     * when the credentials are blank). Release builds refuse plain-http URLs, and this variant is
     * the release configuration — the server URL must be `https://`.
     *
     * Only the first iteration signs in, sets the scope and syncs; the session and the synced
     * library outlive the process kill between iterations, so later iterations go straight to
     * browsing. Playback is deliberately absent: a real stream exercises the same Media3 surface
     * the demo journey already profiles, at the mercy of the server's transcoding and timing.
     */
    @Test
    fun generateSyncJourney() {
        val args = InstrumentationRegistry.getArguments()
        val serverUrl = args.getString("jellyfinServerUrl").orEmpty()
        val username = args.getString("jellyfinUsername").orEmpty()
        val password = args.getString("jellyfinPassword").orEmpty()
        val indexUrl = args.getString("jellyfinIndexUrl").orEmpty()
        val syncFolder = args.getString("jellyfinSyncFolder").orEmpty()
        assumeTrue(
            "no .test.env credentials — skipping the real-server journey",
            serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
        )

        rule.collect(packageName = PACKAGE) {
            pressHome()
            startActivityAndWait()
            returnToTopLevel()
            device.wait(Until.findObject(By.text(TAB_SETTINGS)), TIMEOUT_MS)?.click()
            device.waitForIdle()

            // The password field only exists while signed out, so it is the signed-out marker —
            // the sign-in/sign-out button sits below the fold and cannot be probed cheaply.
            if (device.wait(Until.hasObject(By.res(PASSWORD_FIELD)), TIMEOUT_MS)) {
                signInAndFirstSync(serverUrl, username, password, indexUrl, syncFolder)
            }

            device.wait(Until.findObject(By.text(TAB_LIBRARY)), TIMEOUT_MS)?.click()
            // The first iteration waits out the real network sync here; later ones find the
            // library already populated from Room.
            await(By.res(LIBRARY_ROW), SYNC_TIMEOUT_MS) {
                "The library never filled after Sync now — server down mid-run, or the scope in " +
                    "JELLYFIN_SYNC_FOLDER holds no videos."
            }
            val list = await(By.res(LIBRARY_LIST), TIMEOUT_MS) {
                "Library rows exist but the list container does not — the resource-id bridge broke."
            }
            list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
            repeat(SCROLLS) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
            list.fling(Direction.UP)
            device.waitForIdle()

            // Detail over a real item: Coil decodes a network image instead of a bundled asset.
            openDetails()
            device.waitForIdle()

            returnToTopLevel()
        }
    }

    /**
     * First-iteration setup on the Settings screen: fill the form, sign in (which clears the demo
     * data the earlier tests seeded — the screen announces exactly that), point the index at
     * `.test.env`'s URL or the app's own fill-from-server default, scope the sync when
     * `JELLYFIN_SYNC_FOLDER` names a folder, and start the first sync.
     */
    private fun MacrobenchmarkScope.signInAndFirstSync(
        serverUrl: String,
        username: String,
        password: String,
        indexUrl: String,
        syncFolder: String,
    ) {
        setText(SERVER_URL_FIELD, serverUrl)
        setText(USERNAME_FIELD, username)
        setText(PASSWORD_FIELD, password)
        scrollTo(By.text(SIGN_IN)) { "The Sign in button never scrolled into view." }.click()
        check(device.wait(Until.hasObject(By.text(SIGN_OUT)), SIGN_IN_TIMEOUT_MS)) {
            "Sign in never completed — server down, wrong credentials in .test.env, or a " +
                "plain-http server URL, which this release-configured variant refuses."
        }

        // Signing in over the demo library wiped it, which also zeroed the last-sync marker — so
        // the index field is unlocked and blank, offering Fill. Tapping it applies the same
        // <server>/jellyshelf-index.json convention .test.env leaves implicit; an explicit
        // JELLYFIN_INDEX_URL is typed instead.
        if (indexUrl.isNotBlank()) {
            scrollTo(By.res(INDEX_URL_FIELD)) { "The index URL field never scrolled into view." }
            setText(INDEX_URL_FIELD, indexUrl)
        } else {
            scrollTo(By.text(FILL)) { "The index Fill button never appeared after sign-in." }.click()
        }

        if (syncFolder.isNotBlank()) {
            scrollTo(By.text(CHANGE_FOLDER)) { "The sync-scope section never appeared." }.click()
            // Walk the picker one browse level at a time, the way a user reaches the folder.
            for (segment in syncFolder.split("/").map { it.trim() }.filter { it.isNotEmpty() }) {
                scrollTo(By.text(segment)) {
                    "Folder \"$segment\" of JELLYFIN_SYNC_FOLDER never appeared in the browser."
                }.click()
                device.waitForIdle()
            }
            scrollTo(By.text(USE_THIS_FOLDER)) { "The Use this folder button never appeared." }.click()
        }

        scrollTo(By.text(SYNC_NOW)) { "The Sync now button never scrolled into view." }.click()
        if (syncFolder.isBlank()) {
            // With the scope still at the root, the first tap only nudges "Check the sync scope
            // first" — the second one syncs.
            device.waitForIdle()
            scrollTo(By.text(SYNC_NOW)) { "The Sync now button vanished after the nudge." }.click()
        }
    }

    /**
     * Opens some video's detail screen. A row is two targets — the thumbnail plays, the text
     * beside it opens the details — so this clicks a details zone, and specifically the *tallest*
     * visible one: findObject's first match can be the top-clipped sliver of a half-scrolled row,
     * whose centre falls above both targets and lands on nothing.
     */
    private fun MacrobenchmarkScope.openDetails() {
        await(By.res(ROW_DETAILS), TIMEOUT_MS) {
            "No details zone to open — the rows rendered but carry no $ROW_DETAILS tag."
        }
        device.findObjects(By.res(ROW_DETAILS)).maxBy { it.visibleBounds.height() }.click()
    }

    /** Sets a text field found by its published test tag, without opening the IME. */
    private fun MacrobenchmarkScope.setText(tag: String, value: String) {
        await(By.res(tag), TIMEOUT_MS) {
            "Text field \"$tag\" not on screen — was its testTag removed from SettingsScreen?"
        }.text = value
    }

    /**
     * [UiObject2] for [selector], scrolling the screen's scrollable container toward it when it
     * starts below the fold. Same throw-rather-than-silence contract as [await].
     */
    private fun MacrobenchmarkScope.scrollTo(
        selector: BySelector,
        describe: () -> String,
    ): UiObject2 {
        repeat(MAX_SCROLLS) {
            device.findObject(selector)?.let { return it }
            val scrollable = device.findObject(By.scrollable(true)) ?: return@repeat
            scrollable.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
            scrollable.scroll(Direction.DOWN, SCROLL_STEP)
            device.waitForIdle()
        }
        return await(selector, TIMEOUT_MS, describe)
    }

    /**
     * Presses back until the bottom navigation bar is on screen, i.e. until some top-level tab is
     * showing. Bounded, and a no-op when one already is.
     */
    private fun MacrobenchmarkScope.returnToTopLevel() {
        repeat(MAX_BACK_PRESSES) {
            if (device.hasObject(By.text(TAB_LIBRARY))) return
            device.pressBack()
            device.waitForIdle()
        }
    }

    /**
     * [UiObject2] for [selector] or a failed generation. Null-safe calls are reserved for the
     * genuinely optional steps above; everything else is load-bearing, and silence is the one
     * outcome worse than a failure here.
     */
    private fun MacrobenchmarkScope.await(
        selector: BySelector,
        timeoutMs: Long,
        describe: () -> String,
    ): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), timeoutMs), describe)

    private companion object {
        const val PACKAGE = "com.gmail.volkovskiyda.jellyshelf"

        /** Resource ids, published from Compose test tags by `testTagsAsResourceId`. */
        const val LIBRARY_LIST = "library_list"
        const val LIBRARY_ROW = "library_row"
        const val ROW_DETAILS = "video_row_details"
        const val SERVER_URL_FIELD = "server_url_field"
        const val USERNAME_FIELD = "username_field"
        const val PASSWORD_FIELD = "password_field"
        const val INDEX_URL_FIELD = "index_url_field"

        /** Visible text, for the controls with no tag of their own. */
        const val TRY_DEMO = "Try demo"
        const val PLAY = "Play"
        const val TAB_LIBRARY = "Library"
        const val TAB_CATEGORIES = "Categories"
        const val TAB_SETTINGS = "Settings"
        const val SIGN_IN = "Sign in"
        const val SIGN_OUT = "Sign out"
        const val CHANGE_FOLDER = "Change folder…"
        const val USE_THIS_FOLDER = "Use this folder"
        const val SYNC_NOW = "Sync now"
        const val FILL = "Fill"

        const val TIMEOUT_MS = 5_000L

        /** Generous: seeding writes ~60 videos and their categories to a real database. */
        const val SEED_TIMEOUT_MS = 30_000L

        /** A real network round trip to AuthenticateByName, not a local seed. */
        const val SIGN_IN_TIMEOUT_MS = 15_000L

        /** The first sync fetches the scoped library, the index and thumbnails over the network. */
        const val SYNC_TIMEOUT_MS = 120_000L

        const val SCROLLS = 3
        const val GESTURE_MARGIN_FRACTION = 5
        const val SCROLL_STEP = 0.6f
        const val MAX_SCROLLS = 6

        /** Deep enough for player → detail → library, with room to spare. */
        const val MAX_BACK_PRESSES = 5
    }
}
