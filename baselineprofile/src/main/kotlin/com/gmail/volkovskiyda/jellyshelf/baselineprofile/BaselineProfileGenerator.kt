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
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.regex.Pattern

/**
 * Writes the baseline profile that ships in the release APK, by driving the real app through the
 * paths a user hits first: connecting to a server, the first sync, the library list and its scroll,
 * a video's detail screen, and playback.
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
 * **It profiles a real server when one is configured.** With `.test.env` filled — the same file the
 * live-endpoint tests read — the journey signs in, syncs a real library and streams a real video, so
 * the profile carries what a real first launch actually loads: `AuthenticateByName`, Ktor over TLS,
 * kotlinx.serialization against live responses, sync writing into Room, Coil fetching thumbnails
 * over the network, and ExoPlayer's streaming path. Demo mode reaches none of that — its videos are
 * a bundled asset and its clip is a local file.
 *
 * Without `.test.env` it falls back to demo mode, so a checkout with no server can still regenerate
 * a profile. That fallback is the second-best profile, not the reference one: it costs the release
 * APK every class the network and streaming paths need. The trade the real path makes is
 * determinism — two profiles now differ when the *library* changes as well as when the app does,
 * which is accepted deliberately, because a profile is only worth what it compiles for real use.
 *
 * The run inherits whatever state the installed app is in: the tests below detect it from the
 * screen rather than assuming a fresh install (see [generate1Connect]), and a run against an
 * already-synced install simply profiles less of the connect path. For a true first-use profile,
 * clear the app's data before generating — the runbook says how.
 *
 * The selectors that carry the run are resource ids published by `testTagsAsResourceId` on the root
 * Scaffold, because UiAutomator cannot see Compose test tags otherwise and copy is not a contract.
 * The rest match visible text or content descriptions, on controls with no tag of their own. Every
 * lookup that matters goes through [await] or [check], which throw rather than returning null: a
 * selector that quietly stopped matching would otherwise yield a profile covering the launch and
 * nothing else, with nothing to show for it.
 */
@RunWith(AndroidJUnit4::class)
// The three tests are one sequence — connect, then use, then measure a launch into what that left
// behind — so they are numbered rather than named in a way that happens to sort right.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
// Three tests over eleven named steps. Inlining the steps into the tests is the only way to reduce
// the count, and it would bury the click path this file exists to document — the same trade
// JellyfinApi makes for its endpoints.
@Suppress("TooManyFunctions")
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Live-server config, from the instrumentation runner arguments that Gradle's
     * `loadEnv(".test.env")` feeds in. Blank when the file is absent, which is what selects the
     * demo fallback.
     */
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("jellyfinServerUrl").orEmpty()
    private val username = args.getString("jellyfinUsername").orEmpty()
    private val password = args.getString("jellyfinPassword").orEmpty()
    private val indexUrl = args.getString("jellyfinIndexUrl").orEmpty()
    private val syncFolder = args.getString("jellyfinSyncFolder").orEmpty()

    /**
     * The application id of the app being profiled, from the same runner arguments — read off the
     * tested APK's own metadata by `:baselineprofile`'s build script, rather than written down here.
     * It is `com.gmail.volkovskiyda.jellyshelf.benchmark`: the profiling variants carry a
     * `.benchmark` suffix (see `:app`'s `finalizeDsl`) so a run installs beside the release build
     * on the device instead of replacing it and taking it away again on teardown. Hardcoding the
     * id would silently profile the wrong install the next time either half moved.
     */
    private val targetPackage = requireNotNull(args.getString("targetAppId")) {
        "targetAppId runner argument missing — it is set in baselineprofile/build.gradle.kts, so " +
            "this run is not the one Gradle configures. Use ./gradlew :app:generateReleaseBaselineProfile."
    }

    /** Whether to drive a real server. Release builds refuse plain http, so the URL must be https. */
    private val liveServer: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /**
     * Getting a library on screen: sign in and sync a real one, or seed the demo. Everything the
     * app does once rather than on every launch lives here — the sign-in exchange, the first sync,
     * the folder browser — and none of it belongs in the startup profile.
     *
     * The state of the install decides how much of this runs. A signed-out app shows the password
     * field; a demo-loaded one offers no **Try demo** button because the demo is already in. So each
     * step is guarded by what is actually on screen, which also makes iterations 2+ cheap: the
     * session and the synced library survive the process kill between them, and this test then does
     * little more than confirm the library is still there.
     */
    @Test
    fun generate1Connect() = rule.collect(packageName = targetPackage) {
        pressHome()
        startActivityAndWait()
        // A run that ended on the player or a detail screen leaves the next iteration somewhere
        // with no bottom bar to steer by.
        returnToTopLevel()

        openTab(TAB_SETTINGS)
        if (liveServer) {
            // The password field exists only while signed out, so it is the signed-out marker: the
            // sign-in button itself is below the fold and cannot be probed cheaply. Signing in also
            // clears any demo library that an earlier run left, which is what keeps a configured
            // run from quietly profiling demo data.
            if (device.wait(Until.hasObject(By.res(PASSWORD_FIELD)), TIMEOUT_MS)) {
                signIn()
                configureIndexAndScope()
            }
            syncIfLibraryEmpty()
        } else {
            // Absent once the demo is loaded — then the library is already seeded and this is a no-op.
            device.wait(Until.findObject(By.text(TRY_DEMO)), TIMEOUT_MS)?.click()
        }

        openTab(TAB_LIBRARY)
        awaitLibrary()
    }

    /**
     * The journey, and the bulk of the profile: the library list and its scroll, a video's detail
     * screen, and playback. Deliberately **not** in the startup profile — that one is stored in the
     * primary dex to be read on every cold start, so putting a video player and two list screens in
     * it would cost startup rather than buy it.
     *
     * Relies on [generate1Connect] having left a populated library, which `@FixMethodOrder`
     * guarantees.
     */
    @Test
    fun generate2Journey() = rule.collect(packageName = targetPackage) {
        // Before anything can open the player — see the note on the grant itself.
        grantMediaNotificationPermission()
        pressHome()
        startActivityAndWait()
        returnToTopLevel()
        openTab(TAB_LIBRARY)

        val list = awaitLibrary()
        // Keep the gesture off the display edges, which the system back gesture owns.
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
        repeat(SCROLLS) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()

        // The first video every time, so successive profiles cover the same item rather than
        // whichever row a scroll happened to leave under the selector.
        openFirstVideoDetails()

        // Playback pulls in Media3/ExoPlayer, a large slice of first-use class loading the launch
        // path alone would miss — and against a real server it is the streaming path (HTTP data
        // source, container parsing, codec setup) rather than a bundled file read.
        await(By.text(PLAY), TIMEOUT_MS) { "The detail screen never offered playback." }.click()
        awaitPlaybackUnderway()

        // Out of the player and the detail screen — however many steps that takes — then across to
        // Categories, a different Compose surface.
        returnToTopLevel()
        openTab(TAB_CATEGORIES)
        device.waitForIdle()

        // Finish on the library. The next iteration inherits this as its restored screen, and a
        // top-level tab is the one state it can start from without backing out of anything.
        openTab(TAB_LIBRARY)
        device.waitForIdle()
    }

    /**
     * Cold launch and nothing else — the part ART's dexopt gap hurts most, and the only part worth
     * carrying in the primary dex.
     *
     * It relies on the two tests above having run first: that leaves the app signed in, its library
     * synced and resting on the Library tab, so this measures a launch into a populated list — what
     * a returning user gets — rather than into the first-run Settings screen. Connecting here
     * instead would put the whole sign-in and sync path into the startup profile.
     */
    @Test
    fun generate3Startup() = rule.collect(
        packageName = targetPackage,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        await(By.res(LIBRARY_LIST), TIMEOUT_MS) {
            "Cold launch did not land on the library. generate1Connect and generate2Journey must " +
                "run first — check that @FixMethodOrder(NAME_ASCENDING) still orders them."
        }
    }

    // --- The connect steps ------------------------------------------------------------------

    /** Fills the sign-in form from `.test.env` and waits for the server to accept it. */
    private fun MacrobenchmarkScope.signIn() {
        setText(SERVER_URL_FIELD, serverUrl)
        setText(USERNAME_FIELD, username)
        setText(PASSWORD_FIELD, password)
        scrollTo(By.text(SIGN_IN)) { "The Sign in button never scrolled into view." }.click()
        check(device.wait(Until.hasObject(By.text(SIGN_OUT)), SIGN_IN_TIMEOUT_MS)) {
            "Sign in never completed — server down, wrong credentials in .test.env, or a " +
                "plain-http server URL, which this release-configured variant refuses."
        }
    }

    /**
     * The optional fields, in the order the screen offers them once a sign-in lands: the metadata
     * index URL, then the sync scope.
     *
     * The index field appears only after sign-in and offers **Fill**, which writes
     * `<server>/jellyshelf-index.json` — the same convention `.test.env` leaves implicit when
     * `JELLYFIN_INDEX_URL` is blank. An explicit URL is typed instead.
     */
    private fun MacrobenchmarkScope.configureIndexAndScope() {
        if (indexUrl.isNotBlank()) {
            scrollTo(By.res(INDEX_URL_FIELD)) { "The index URL field never appeared after sign-in." }
            setText(INDEX_URL_FIELD, indexUrl)
        } else {
            scrollTo(By.text(FILL)) { "The index Fill button never appeared after sign-in." }.click()
        }

        if (syncFolder.isBlank()) return
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

    /**
     * Runs the first sync, unless a previous run's library is already there — the rows survive the
     * process kill between iterations, and re-syncing each time would spend the run on the network
     * rather than on the paths being profiled.
     */
    private fun MacrobenchmarkScope.syncIfLibraryEmpty() {
        openTab(TAB_LIBRARY)
        if (device.wait(Until.hasObject(By.res(LIBRARY_ROW)), TIMEOUT_MS)) return

        openTab(TAB_SETTINGS)
        scrollTo(By.text(SYNC_NOW)) { "The Sync now button never scrolled into view." }.click()
        if (syncFolder.isBlank()) {
            // With the scope still at the root, the first tap only nudges "Check the sync scope
            // first" — the second one syncs.
            device.waitForIdle()
            scrollTo(By.text(SYNC_NOW)) { "The Sync now button vanished after the nudge." }.click()
        }
    }

    // --- Shared steps -----------------------------------------------------------------------

    /** The library list, once it has rows. Allows for a real first sync over the network. */
    private fun MacrobenchmarkScope.awaitLibrary(): UiObject2 {
        await(By.res(LIBRARY_ROW), SYNC_TIMEOUT_MS) {
            if (liveServer) {
                "The library never filled. The server may be unreachable, or the scope in " +
                    "JELLYFIN_SYNC_FOLDER may hold no videos."
            } else {
                "The demo library never seeded, or the resource-id bridge is gone — check " +
                    "testTagsAsResourceId on MainActivity's root Scaffold."
            }
        }
        return await(By.res(LIBRARY_LIST), TIMEOUT_MS) {
            "Library rows exist but the list container does not — the resource-id bridge broke."
        }
    }

    /**
     * Opens the first video's detail screen.
     *
     * A row is two targets — the thumbnail plays, the text beside it opens the details — so this
     * clicks a details zone. The *topmost* one, by position rather than by match order: after a
     * scroll the first match can be a row clipped by the top bar, whose centre falls outside both
     * targets and lands on nothing.
     */
    private fun MacrobenchmarkScope.openFirstVideoDetails() {
        await(By.res(ROW_DETAILS), TIMEOUT_MS) {
            "No details zone to open — the rows rendered but carry no $ROW_DETAILS tag."
        }
        val first = device.findObjects(By.res(ROW_DETAILS))
            .filter { it.visibleBounds.height() >= MIN_TAPPABLE_PX }
            .minByOrNull { it.visibleBounds.top }
        checkNotNull(first) { "Every library row was clipped — nothing safe to tap." }.click()
        device.waitForIdle()
    }

    /**
     * Grants `POST_NOTIFICATIONS` before the player can ask for it.
     *
     * `PlayerScreen` requests it the first time it opens — the media notification is the only way
     * back into a playing video — and on the freshly installed APK a generation run uses, that puts
     * a system dialog *over* the player. Playback carries on behind it, so nothing fails except this
     * journey's own check, which then waits out its timeout looking at a dialog. The dialog belongs
     * to another process and profiling it would buy the app nothing, so the deterministic answer is
     * to grant it up front. Idempotent, and a no-op once granted.
     */
    private fun MacrobenchmarkScope.grantMediaNotificationPermission() {
        device.executeShellCommand("pm grant $targetPackage android.permission.POST_NOTIFICATIONS")
    }

    /**
     * Waits for playback to actually start, rather than for the player screen to appear: the point
     * of this step is the streaming path, and a player sitting on a failed load would profile none
     * of it.
     *
     * The signal is the *elapsed-position label*, once it reads something other than zero. The
     * pause button is not enough and used to be what this waited for: the icon follows the
     * play/pause intent, so it appears the moment the tap lands — before a byte is fetched, with
     * the seek bar still disabled for want of a duration. A run could pass this check and profile a
     * player that never streamed, which is the one failure the whole live-server setup exists to
     * avoid. The position only moves when frames do.
     */
    private fun MacrobenchmarkScope.awaitPlaybackUnderway() {
        if (playing(PLAYBACK_TIMEOUT_MS)) return

        // Two things hide a playing video's controls, and they need opposite handling: a permission
        // dialog sits *over* them, so dismissing it reveals controls that were there all along,
        // while the player's own auto-hide needs a tap to bring them back. Tapping blind would
        // switch the controls off in the first case, so try the dialog first and re-check between.
        device.findObject(By.res(ALLOW_PERMISSION_BUTTON))?.let { allow ->
            allow.click()
            if (playing(TIMEOUT_MS)) return
        }
        // One tap, never a loop: two in quick succession are a double tap, which seeks.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        check(playing(TIMEOUT_MS)) {
            if (liveServer) {
                "Playback never got past 0:00. The server may be refusing to stream this item, or " +
                    "the playback mode may have been left on an external player."
            } else {
                "The bundled demo clip never played."
            }
        }
    }

    /**
     * Whether the player's position label has moved off zero, i.e. the video is really rolling.
     *
     * Absent while the controls are hidden — they go three seconds into playing — which is a false
     * negative the caller answers with a tap, not with a retry loop of its own.
     */
    private fun MacrobenchmarkScope.playing(timeoutMs: Long): Boolean = device.wait(
        Until.hasObject(By.res(PLAYER_POSITION).text(Pattern.compile("(?!^$ZERO_POSITION$).+"))),
        timeoutMs,
    )

    /** Taps a bottom-navigation tab, waiting for it rather than assuming it is already there. */
    private fun MacrobenchmarkScope.openTab(label: String) {
        await(By.text(label), TIMEOUT_MS) { "The $label tab is not on screen." }.click()
        device.waitForIdle()
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
        /** Resource ids, published from Compose test tags by `testTagsAsResourceId`. */
        const val LIBRARY_LIST = "library_list"
        const val LIBRARY_ROW = "library_row"
        const val ROW_DETAILS = "video_row_details"
        const val SERVER_URL_FIELD = "server_url_field"
        const val USERNAME_FIELD = "username_field"
        const val PASSWORD_FIELD = "password_field"
        const val INDEX_URL_FIELD = "index_url_field"

        /** The player's elapsed-position label, and what it reads before anything has played. */
        const val PLAYER_POSITION = "player_position"
        const val ZERO_POSITION = "0:00"

        /** Visible text, for the controls with no tag of their own. */
        const val TRY_DEMO = "Try demo"
        const val PLAY = "Play"
        const val TAB_LIBRARY = "Library"
        const val TAB_CATEGORIES = "Categories"
        const val TAB_SETTINGS = "Settings"
        const val SIGN_IN = "Sign in"
        const val SIGN_OUT = "Sign out"
        const val FILL = "Fill"
        const val CHANGE_FOLDER = "Change folder…"
        const val USE_THIS_FOLDER = "Use this folder"
        const val SYNC_NOW = "Sync now"

        /** The system permission dialog's grant button, by id so it does not depend on locale. */
        const val ALLOW_PERMISSION_BUTTON = "com.android.permissioncontroller:id/permission_allow_button"

        const val TIMEOUT_MS = 5_000L

        /** A real network round trip to AuthenticateByName, not a local check. */
        const val SIGN_IN_TIMEOUT_MS = 15_000L

        /**
         * A real first sync fetches the scoped library, the index and thumbnails over the network;
         * a demo seed writes ~60 videos and their categories to a real database.
         */
        const val SYNC_TIMEOUT_MS = 120_000L

        /** A real stream may transcode before the first frame arrives. */
        const val PLAYBACK_TIMEOUT_MS = 30_000L

        const val SCROLLS = 3
        const val GESTURE_MARGIN_FRACTION = 5
        const val SCROLL_STEP = 0.6f
        const val MAX_SCROLLS = 6

        /** Below this a row is clipped enough that its centre may miss both click targets. */
        const val MIN_TAPPABLE_PX = 100

        /** Deep enough for player → detail → library, with room to spare. */
        const val MAX_BACK_PRESSES = 5
    }
}
