// The steps below are one journey split into named clicks, and every one of them is load-bearing
// for both tests in this module. Inlining them to satisfy the threshold would bury the click path
// this file exists to document — the same trade BaselineProfileGenerator already makes.
@file:Suppress("TooManyFunctions")

package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

// Getting the app from *just installed* to *a populated library on screen*, and the handles that
// steer it there.
//
// Both tests in this module need that state and neither may assume it. BaselineProfileGenerator
// needs it because a profile written against an empty first-run screen compiles the wrong code;
// JourneyBenchmark needs it because a cold launch into an empty list is fast for reasons that have
// nothing to do with the profile it is measuring.
//
// These steps used to belong to the generator alone, and the benchmark carried a note telling you
// to run the generator first. That could not work, and the failure was silent in the worst way — it
// read as a broken benchmark. Two things stand in the way. The generator's three tests are skipped
// outside the `nonMinifiedRelease` variant, because BaselineProfileRule only collects there, so a
// `benchmarkRelease` run drives nothing. And AGP's connected-test teardown *uninstalls the app under
// test* when a run ends, taking its data with it — so even the signed-in, synced install a real
// generation run leaves behind is gone before the next run's install replaces it. Sharing the steps
// is what lets the benchmark reach the state it needs on a device that has never generated a
// profile.
//
// The selectors are resource ids published by `testTagsAsResourceId` on MainActivity's root
// Scaffold, because UiAutomator cannot see Compose test tags otherwise and copy is not a contract.
// The rest match visible text, on controls with no tag of their own. Every lookup that matters goes
// through `await` or `check`, which throw rather than returning null: a selector that quietly
// stopped matching would otherwise yield a profile covering the launch and nothing else, or a
// benchmark number measured against a screen nobody looked at.

/**
 * Leaves the app signed in, synced and resting on a populated Library tab.
 *
 * Everything the app does once rather than on every launch lives here — the sign-in exchange, the
 * first sync, the folder browser. The state of the install decides how much of it actually runs: a
 * signed-out app shows the password field; a demo-loaded one offers no **Try demo** button because
 * the demo is already in. So each step is guarded by what is on screen, which is what makes a
 * second call cheap — the session and the synced library survive a process kill, and this then does
 * little more than confirm the library is still there.
 */
internal fun MacrobenchmarkScope.ensureLibrary() {
    // Before the library has anything in it — see the note on the grant itself.
    grantJourneyPermissions()
    pressHome()
    startActivityAndWait()
    // A run that ended on the player or a detail screen leaves the next one somewhere with no
    // bottom bar to steer by.
    returnToTopLevel()

    openTab(TAB_SETTINGS)
    if (JourneyConfig.liveServer) {
        // The password field exists only while signed out, so it is the signed-out marker: the
        // sign-in button itself is below the fold and cannot be probed cheaply. Signing in also
        // clears any demo library that an earlier run left, which is what keeps a configured run
        // from quietly working against demo data.
        if (device.wait(Until.hasObject(By.res(PASSWORD_FIELD)), TIMEOUT_MS)) signIn()
        syncIfLibraryEmpty()
    } else {
        // Absent once the demo is loaded — then the library is already seeded and this is a no-op.
        device.wait(Until.findObject(By.text(TRY_DEMO)), TIMEOUT_MS)?.click()
    }

    openTab(TAB_LIBRARY)
    awaitLibrary()
}

/** Fills the sign-in form from `.test.env` and waits for the server to accept it. */
internal fun MacrobenchmarkScope.signIn() {
    setText(SERVER_URL_FIELD, JourneyConfig.serverUrl)
    setText(USERNAME_FIELD, JourneyConfig.username)
    setText(PASSWORD_FIELD, JourneyConfig.password)
    scrollTo(By.text(SIGN_IN)) { "The Sign in button never scrolled into view." }.click()
    check(
        device.wait(Until.hasObject(By.text(SIGN_OUT)), SIGN_IN_TIMEOUT_MS),
        diagnose {
            "Sign in never completed — server down, wrong credentials in .test.env, a plain-http " +
                "server URL, which this release-configured variant refuses, or a server on the " +
                "local network the app is not allowed to reach (see grantJourneyPermissions)."
        },
    )
}

/**
 * The optional fields, in the order the screen offers them once a sign-in lands: the metadata index
 * URL, then the sync scope. Called from [syncIfLibraryEmpty], because both are only worth anything
 * to a sync that is about to run — and re-walking them costs a signed-in run nothing but the clicks.
 *
 * The index field appears only after sign-in and offers **Fill**, which writes
 * `<server>/jellyshelf-index.json` — the same convention `.test.env` leaves implicit when
 * `JELLYFIN_INDEX_URL` is blank. An explicit URL is typed instead.
 */
internal fun MacrobenchmarkScope.configureIndexAndScope() {
    if (JourneyConfig.indexUrl.isNotBlank()) {
        scrollTo(By.res(INDEX_URL_FIELD)) { "The index URL field never appeared after sign-in." }
        setText(INDEX_URL_FIELD, JourneyConfig.indexUrl)
    } else {
        scrollTo(By.text(FILL)) { "The index Fill button never appeared after sign-in." }.click()
    }

    val syncFolder = JourneyConfig.syncFolder
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
 * rather than on the paths being driven.
 */
internal fun MacrobenchmarkScope.syncIfLibraryEmpty() {
    openTab(TAB_LIBRARY)
    if (device.wait(Until.hasObject(By.res(LIBRARY_ROW)), TIMEOUT_MS)) return

    openTab(TAB_SETTINGS)
    // Immediately before the sync rather than immediately after the sign-in, which is where this
    // used to be. The two are the same moment only on a run that signs in; a run that finds the app
    // already signed in — because the one before it fell over somewhere after the sign-in and the
    // session outlived it — would skip the scope entirely and then sync the root, which syncs
    // nothing and fails two minutes later on an empty library. Tying it to the sync it is a
    // precondition for makes the order true in both cases.
    configureIndexAndScope()
    scrollTo(By.text(SYNC_NOW)) { "The Sync now button never scrolled into view." }.click()
    if (JourneyConfig.syncFolder.isBlank()) {
        // With the scope still at the root, the first tap only nudges "Check the sync scope
        // first" — the second one syncs.
        device.waitForIdle()
        scrollTo(By.text(SYNC_NOW)) { "The Sync now button vanished after the nudge." }.click()
    }
    awaitSyncFinished()
}

/**
 * Waits for the sync to finish, not merely to have produced something.
 *
 * The screen reports it: `SettingsViewModel.observeSync` mirrors the worker's `WorkInfo.State` into
 * a status line that reads "Syncing…" while the work is enqueued, blocked or running, so the line
 * going away is WorkManager's own answer rather than a guess about elapsed time.
 *
 * Waiting for it is not politeness. Rows appearing is the *middle* of a sync, and a benchmark that
 * starts while WorkManager still has live work fails in a way that names neither: `StartupMode.COLD`
 * checks the app is gone the instant macrobenchmark force-stops it, and WorkManager's
 * `ForceStopRunnable` restarts the process to reschedule what is queued, so the check loses a race
 * it does not know it is in and reports `Package … must not be running prior to cold start!`. On a
 * Pixel 5 the first sync was still running seven seconds after the rows arrived, which is where
 * this was found; the tablet had finished by then and passed, twice.
 *
 * The first wait is for the line to *appear*, because a tap that has not reached WorkManager yet
 * would otherwise look exactly like a sync that has already finished.
 */
private fun MacrobenchmarkScope.awaitSyncFinished() {
    device.wait(Until.hasObject(By.text(SYNCING)), TIMEOUT_MS)
    check(device.wait(Until.gone(By.text(SYNCING)), SYNC_TIMEOUT_MS)) {
        "The sync never finished — it is still running after ${SYNC_TIMEOUT_MS / MILLIS_PER_SECOND}s."
    }
}

/**
 * Force-stops the app until it stays stopped, so a cold-start measurement starts from a process
 * that is really gone.
 *
 * [awaitSyncFinished] removes the usual reason it would not be — but it is the *precondition* that
 * matters here, not any one cause of missing it, and anything the app schedules in the background
 * can restart the process behind a force-stop. Asserting it here fails on the thing that is
 * actually wrong, before macrobenchmark's own check fails on a symptom.
 *
 * Each attempt is also what drains the queue: a restart from `ForceStopRunnable` reschedules and
 * then has nothing left to do, so the next kill sticks.
 */
internal fun MacrobenchmarkScope.awaitStoppable() {
    repeat(STOP_ATTEMPTS) {
        killProcess()
        Thread.sleep(STOP_SETTLE_MS)
        if (!running()) return
    }
    error(
        "The app keeps restarting itself after a force-stop, so no cold start can be measured. " +
            "Something it schedules in the background is bringing the process back — WorkManager " +
            "does exactly this while it still has work queued.",
    )
}

/** Whether the app under test has a live process. */
private fun MacrobenchmarkScope.running(): Boolean =
    device.executeShellCommand("pidof ${JourneyConfig.targetPackage}").isNotBlank()

/**
 * Waits for the library list to have rows in it, or fails the run.
 *
 * [rowsTimeoutMs] defaults to the budget a real first sync needs. A caller that has already been
 * through [ensureLibrary] and only wants to re-confirm the list — the benchmark, inside a block it
 * is timing — passes something shorter, because there the rows are either already there or the run
 * is over.
 */
internal fun MacrobenchmarkScope.awaitLibrary(rowsTimeoutMs: Long = SYNC_TIMEOUT_MS) {
    await(By.res(LIBRARY_ROW), rowsTimeoutMs) {
        if (JourneyConfig.liveServer) {
            "The library never filled. The server may be unreachable, or the scope in " +
                "JELLYFIN_SYNC_FOLDER may hold no videos."
        } else {
            "The demo library never seeded, or the resource-id bridge is gone — check " +
                "testTagsAsResourceId on MainActivity's root Scaffold."
        }
    }
    await(By.res(LIBRARY_LIST), TIMEOUT_MS) {
        "Library rows exist but the list container does not — the resource-id bridge broke."
    }
}

/**
 * Grants the two runtime permissions the journey must never be asked about, before it can be.
 *
 * `POST_NOTIFICATIONS`: the library screen asks the first time it has videos in it — the media
 * notification is the only way back into a playing video — and on the freshly installed APK these
 * runs use, that puts a dialog over the very list they scroll, with the system's own behind it if
 * it is answered. Everything carries on behind them, so nothing fails except the checks, which then
 * wait out their timeouts looking at a dialog.
 *
 * `ACCESS_LOCAL_NETWORK`: from Android 16, reaching a Jellyfin on the same LAN needs it on top of
 * `INTERNET`, and without it the connection is dropped rather than refused — sign-in hangs until
 * Ktor's own 30-second timeout and reports the server as unreachable. The app declares it, so the
 * install grant normally carries it; this makes a run deterministic on a device where it was
 * revoked by hand. Unknown below API 36, where `pm grant` fails and the run is unaffected.
 *
 * Neither dialog belongs to what is being profiled or measured. Both grants are idempotent, and
 * no-ops once granted.
 */
internal fun MacrobenchmarkScope.grantJourneyPermissions() {
    for (permission in GRANTED_PERMISSIONS) {
        device.executeShellCommand("pm grant ${JourneyConfig.targetPackage} $permission")
    }
}

/** Taps a bottom-navigation tab, waiting for it rather than assuming it is already there. */
internal fun MacrobenchmarkScope.openTab(label: String) {
    await(By.text(label), TIMEOUT_MS) { "The $label tab is not on screen." }.click()
    device.waitForIdle()
}

/**
 * Presses back until the bottom navigation bar is on screen, i.e. until some top-level tab is
 * showing. Bounded, and a no-op when one already is.
 */
internal fun MacrobenchmarkScope.returnToTopLevel() {
    repeat(MAX_BACK_PRESSES) {
        if (device.hasObject(By.text(TAB_LIBRARY))) return
        device.pressBack()
        device.waitForIdle()
    }
}

/**
 * Waits until nothing is playing any more, using the mini-player bar as the signal: the bar is shown
 * exactly when `NowPlayingState.nowPlaying` is non-null, so its stop button leaving the screen is
 * the app itself saying the session is over.
 *
 * **What this is actually worth, measured rather than assumed.** It was added to fix iterations
 * dying on `check(!Shell.isPackageAlive(packageName))` — "Package must not be running prior to cold
 * start!" — where the theory was that a stop still in flight left a live session for the system to
 * restart. Adding it took a failing variant from 1 completed iteration to 4.
 *
 * **But the check itself never fires.** The bar is already gone every time it is asked, so playback
 * had stopped; what helped was the moment spent asking. Read this as a settling point with an
 * assertion attached, not as the cure — and note it is **not sufficient**: a run still failed on the
 * fifth iteration with the same message, so something else restarts the process. The likeliest
 * remaining suspect is the system re-binding the session for its media-resumption UI, which is
 * exactly what `PlaybackService.onPlaybackResumption` exists to answer.
 *
 * The assertion earns its place regardless: if a stop ever genuinely hangs, this fails the iteration
 * that caused it instead of the next one, which is what made the original fault cost three runs to
 * find.
 *
 * Returns quietly if the bar was never there — a journey that stopped playback by other means, or
 * one that never started any.
 */
internal fun MacrobenchmarkScope.awaitPlaybackStopped() {
    check(device.wait(Until.gone(By.desc(MINI_PLAYER_STOP)), PLAYBACK_STOP_TIMEOUT_MS)) {
        "Playback was still running $PLAYBACK_STOP_TIMEOUT_MS ms after backing out to the library. " +
            "The mini-player bar is still on screen, so the session never ended — the next " +
            "iteration's cold start will fail on a process that restarted itself."
    }
}

/** Sets a text field found by its published test tag, without opening the IME. */
internal fun MacrobenchmarkScope.setText(tag: String, value: String) {
    await(By.res(tag), TIMEOUT_MS) {
        "Text field \"$tag\" not on screen — was its testTag removed from SettingsScreen?"
    }.text = value
}

/**
 * [UiObject2] for [selector], scrolling the screen's scrollable container toward it when it starts
 * below the fold. Same throw-rather-than-silence contract as [await].
 *
 * Three things this has to get right, each of which cost a tablet run before it was understood.
 *
 * **A match is not necessarily a usable match.** Compose clips a scrolling column's children to the
 * container, and a child clipped to its edge is still in the accessibility tree — "Change folder…"
 * came back two pixels tall, whose centre is the container boundary, so a click on it landed on
 * nothing and the steps after it failed somewhere unrelated. [unclipped] is what rejects that.
 *
 * **A scroll has to actually move.** This used to call [UiObject2.scroll], which does nothing at all
 * here and reports nothing when it doesn't; [scrollScreen] swipes the container by hand and says
 * whether the content moved, so a stuck screen fails on the control it was looking for instead of
 * six silent no-ops later.
 *
 * **And the target is not always below.** Searching one way only is the assumption the folder
 * browser breaks — see the comment on the sweep back to the top.
 */
internal fun MacrobenchmarkScope.scrollTo(
    selector: BySelector,
    describe: () -> String,
): UiObject2 {
    // Downwards from wherever the screen already is. The common case is a control just below the
    // fold, and this reaches it without disturbing anything above.
    sweep(Direction.DOWN, selector)?.let { return it }
    // Then the container from its top, because the target may be *above* the current position and
    // no amount of scrolling down will ever reach it. The folder browser is where this bites: it
    // opens inline in a Settings column already scrolled far enough down to reach the button that
    // opened it, so its first entries start off-screen upwards. A run looking for "Folders" saw the
    // fourth through eighth entries of that list and scrolled away from the one it wanted.
    sweepToEnd(Direction.UP)
    sweep(Direction.DOWN, selector)?.let { return it }
    return checkNotNull(device.findObject(selector), diagnose(describe))
}

/** Scrolls [direction] until [selector] has an [unclipped] match, the end arrives, or the attempts run out. */
private fun MacrobenchmarkScope.sweep(direction: Direction, selector: BySelector): UiObject2? {
    repeat(MAX_SCROLLS) {
        unclipped(selector)?.let { return it }
        if (!scrollScreen(direction)) return unclipped(selector)
    }
    return unclipped(selector)
}

/** Scrolls [direction] until the content stops moving, i.e. to that end of the container. */
private fun MacrobenchmarkScope.sweepToEnd(direction: Direction) {
    repeat(MAX_SCROLLS) { if (!scrollScreen(direction)) return }
}

/**
 * The first match the scrolling container is not cutting in half, or null.
 *
 * Flush against the container's top or bottom edge means clipped: the tree reports whatever slice
 * survived the clip, and its centre — which is what a click uses — is at or beyond the edge.
 * Bounded by the container rather than by a pixel count on purpose, because a text label is only a
 * few tens of pixels tall to begin with and any threshold that rejects a sliver would reject one of
 * those on a low-density screen.
 */
private fun MacrobenchmarkScope.unclipped(selector: BySelector): UiObject2? {
    val container = device.findObject(By.scrollable(true))?.boundsOrGone()
        ?: return device.findObject(selector)
    return device.findObjects(selector).firstOrNull {
        val bounds = it.boundsOrGone() ?: return@firstOrNull false
        bounds.top > container.top && bounds.bottom < container.bottom
    }
}

/**
 * Swipes the screen's scrollable container [direction] by most of its height over [steps] motion
 * events, and reports whether anything moved.
 *
 * Only the drag settles afterwards ([awaitContentStill]), not the fling: everything that scrolls to
 * find something then clicks it goes through the drag, and a control located while the screen is
 * still moving is a control the click misses. The fling belongs to the benchmark's measured block,
 * where a wait would sit inside the numbers.
 *
 * A plain [androidx.test.uiautomator.UiDevice.swipe] between two points worked out from the
 * container's own bounds, rather than [UiObject2.scroll] with gesture margins. The margins are the
 * part that broke: they are one number applied to all four sides, and the number this file used to
 * pass was a fraction of the display *width* — which on a landscape tablet is the long edge, so it
 * took 512 px off the top and bottom of a container 1176 px tall and left a gesture too short to
 * scroll anything. Deriving the swipe from the container means the same code covers a portrait
 * phone and a landscape tablet without knowing which it is on.
 *
 * The return value is the guard the old call had no way to offer. A scroll that silently does
 * nothing is indistinguishable from a screen with nothing beyond the fold, and every caller here
 * needs to tell those apart — [sweepToEnd] has nothing else to stop on.
 */
private fun MacrobenchmarkScope.scrollScreen(direction: Direction): Boolean =
    swipeScreen(direction, SCROLL_GESTURE_STEPS).also { awaitContentStill() }

/**
 * Flings the screen's scrollable container [direction], and reports whether anything moved.
 *
 * A fling rather than a drag because that is what a frame-timing benchmark is for: dropped frames
 * show up in the sustained animation after the finger leaves the glass, and a slow drag never
 * produces one. Same gesture as [scrollScreen] otherwise, over far fewer steps.
 *
 * Not [UiObject2.fling], which is the dead end [swipeScreen] describes for [UiObject2.scroll] — on
 * the Pixel Tablet it returns without moving the list. Its return value makes that worse rather
 * than catching it: it answers "can this still scroll", so a gesture that never happened is
 * indistinguishable from a library too short to scroll, and both read as a fast benchmark.
 */
internal fun MacrobenchmarkScope.flingScreen(direction: Direction): Boolean =
    swipeScreen(direction, FLING_GESTURE_STEPS)

private fun MacrobenchmarkScope.swipeScreen(direction: Direction, steps: Int): Boolean {
    val bounds = device.findObject(By.scrollable(true))?.boundsOrGone() ?: return false
    val inset = bounds.height() / SCROLL_EDGE_INSET_FRACTION
    val lower = bounds.bottom - inset
    val upper = bounds.top + inset
    // Scrolling down means dragging the content up, and the reverse for up.
    val from = if (direction == Direction.DOWN) lower else upper
    val to = if (direction == Direction.DOWN) upper else lower
    val before = contentOffsets()
    device.swipe(bounds.centerX(), from, bounds.centerX(), to, steps)
    device.waitForIdle()
    return contentOffsets() != before
}

/**
 * Where every piece of text on the app's screen currently sits — a cheap fingerprint of the scroll
 * position, since a scroll that moved shifts all of them and one that did not shifts none.
 *
 * Positions rather than the text itself: the password field's contents are in this tree, and a
 * fingerprint has no need of them.
 */
private fun MacrobenchmarkScope.contentOffsets(): List<Int> = device
    .findObjects(By.pkg(JourneyConfig.targetPackage).clazz(TEXT_VIEW))
    .mapNotNull { it.boundsOrGone()?.top }

/**
 * Waits for the screen to stop moving, i.e. for two consecutive [contentOffsets] readings to agree.
 *
 * [androidx.test.uiautomator.UiDevice.waitForIdle] does not cover a fling. It returns as soon as
 * the accessibility event stream goes quiet, and a list coasting to a stop is quiet between frames
 * — so a caller can be handed a list that is still moving, and every node it then measures is a
 * race against the next recomposition.
 *
 * Returns quietly when the content never settles, rather than failing: a caller waiting for a
 * still list has its own answer for one that will not hold still, and a screen with no text on it
 * at all — two empty readings — is already as still as it will get.
 */
internal fun MacrobenchmarkScope.awaitContentStill() {
    var previous = contentOffsets()
    repeat(STILL_ATTEMPTS) {
        Thread.sleep(STILL_POLL_MS)
        val current = contentOffsets()
        if (current == previous) return
        previous = current
    }
}

/**
 * [UiObject2.getVisibleBounds], or null if the node has left the tree since it was found.
 *
 * Every read here is a fresh IPC to the app, and the screen these run against is moving — a swipe
 * is settling, a list is recomposing. A node found a moment ago can be gone by the time it is
 * measured, and UiAutomator answers that with [StaleObjectException] rather than a null. Left
 * unhandled it surfaces as a bare `StaleObjectException` from the middle of the journey, naming
 * nothing: the run that found this one reported it from `ensureLibrary` with no indication of which
 * step or which node. A node that vanished mid-scan is simply not a candidate.
 */
internal fun UiObject2.boundsOrGone(): Rect? = try {
    visibleBounds
} catch (_: StaleObjectException) {
    null
}

/**
 * The node's text, unless it carries a test tag or has left the tree — one guarded read, since
 * [UiObject2.getResourceName] goes stale exactly as [UiObject2.getVisibleBounds] does.
 *
 * Skipping tagged nodes is what keeps the credentials typed into the four `…_FIELD` fields out of a
 * failure message and therefore out of the build output. In this screen the tagged nodes are
 * precisely those fields; everything a diagnosis wants — labels, status lines, buttons — carries no
 * tag.
 */
private fun UiObject2.untaggedText(): String? = try {
    text?.takeIf { resourceName == null }?.trim()?.takeIf(String::isNotEmpty)
} catch (_: StaleObjectException) {
    null
}

/**
 * [describe] with the app's own words appended — the status line it is showing, the buttons it is
 * offering — because a selector that did not match says nothing about *why*.
 *
 * The one place in this file allowed to depend on copy, since nothing selects on it: it is read out
 * to a human after the run has already failed. It is the difference between "the sync-scope section
 * never appeared" and that plus `Sign-in failed: Request timeout has expired`, which is the whole
 * diagnosis rather than the start of one.
 *
 * Tagged nodes are skipped — see [untaggedText] for what that keeps out of the build output.
 */
internal fun MacrobenchmarkScope.diagnose(describe: () -> String): () -> String = {
    val onScreen = device
        .findObjects(By.pkg(JourneyConfig.targetPackage).clazz(TEXT_VIEW))
        .mapNotNull { it.untaggedText() }
        .distinct()
    "${describe()} The screen says: ${onScreen.joinToString(" / ").ifEmpty { "(nothing)" }}"
}

/**
 * [UiObject2] for [selector] or a failed run. Null-safe calls are reserved for the genuinely
 * optional steps above; everything else is load-bearing, and silence is the one outcome worse than
 * a failure here.
 */
internal fun MacrobenchmarkScope.await(
    selector: BySelector,
    timeoutMs: Long,
    describe: () -> String,
): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), timeoutMs), describe)

/** Resource ids, published from Compose test tags by `testTagsAsResourceId`. */
internal const val LIBRARY_LIST = "library_list"
internal const val LIBRARY_ROW = "library_row"
internal const val ROW_DETAILS = "video_row_details"
internal const val SERVER_URL_FIELD = "server_url_field"
internal const val USERNAME_FIELD = "username_field"
internal const val PASSWORD_FIELD = "password_field"
internal const val INDEX_URL_FIELD = "index_url_field"

/** Visible text, for the controls with no tag of their own. */
internal const val TRY_DEMO = "Try demo"
internal const val TAB_LIBRARY = "Library"
internal const val TAB_CATEGORIES = "Categories"
internal const val TAB_SETTINGS = "Settings"
internal const val SIGN_IN = "Sign in"
internal const val SIGN_OUT = "Sign out"
internal const val FILL = "Fill"
internal const val CHANGE_FOLDER = "Change folder…"
internal const val USE_THIS_FOLDER = "Use this folder"
internal const val SYNC_NOW = "Sync now"

/** The status line the screen shows while WorkManager has the sync enqueued, blocked or running. */
internal const val SYNCING = "Syncing…"

/**
 * Runtime permissions granted up front — see [grantJourneyPermissions] for why each one
 * would otherwise stop the journey rather than merely change it.
 */
internal val GRANTED_PERMISSIONS = listOf(
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.ACCESS_LOCAL_NETWORK",
)

internal const val TIMEOUT_MS = 5_000L

internal const val MILLIS_PER_SECOND = 1_000L

/** Kills allowed before a process that keeps coming back is called a failure rather than a race. */
internal const val STOP_ATTEMPTS = 10

/** A fling settles well inside a second; the polls are only there to notice when it has. */
private const val STILL_ATTEMPTS = 10
private const val STILL_POLL_MS = 100L

/** Long enough for WorkManager's ForceStopRunnable to have restarted the process if it is going to. */
internal const val STOP_SETTLE_MS = 1_500L

/**
 * A real network round trip to AuthenticateByName, not a local check — and longer than the app's
 * own 30-second request timeout on purpose, so a request that is being dropped rather than answered
 * has time to say so on screen before [diagnose] reads the screen.
 */
internal const val SIGN_IN_TIMEOUT_MS = 35_000L

/**
 * A real first sync fetches the scoped library, the index and thumbnails over the network; a demo
 * seed writes ~60 videos and their categories to a real database.
 *
 * Five minutes rather than the two that fitted one device, because a generation run drives every
 * attached device at once and they all sync the same library from the same server at the same
 * moment — while one of them is also streaming a video from it. The Pixel 5 ran past two minutes
 * doing that and failed the run on the very first test; nothing is lost by waiting longer, since
 * the budget only ever elapses on a sync that is not going to finish.
 */
internal const val SYNC_TIMEOUT_MS = 300_000L

/** How many flings a browse is worth, in both the generator and the benchmark. */
internal const val SCROLLS = 3

/** How far in from a scrollable's top and bottom edge a swipe starts and ends. */
internal const val SCROLL_EDGE_INSET_FRACTION = 6

/** Slow enough to be a scroll rather than a fling, so it lands where it is aimed. */
internal const val SCROLL_GESTURE_STEPS = 20

/** Fast enough to leave the list moving after the gesture ends, which is the part worth timing. */
internal const val FLING_GESTURE_STEPS = 5

/**
 * Enough swipes to cross the longest screen here twice over — the Settings column, and the folder
 * browser that opens inside it — since a sweep may have to reach one end before starting for the
 * other.
 */
internal const val MAX_SCROLLS = 10

/** What Compose publishes text as, through `testTagsAsResourceId`'s accessibility bridge. */
internal const val TEXT_VIEW = "android.widget.TextView"

/** Deep enough for player → detail → library, with room to spare. */
internal const val MAX_BACK_PRESSES = 5

/**
 * Opens the first video's detail screen.
 *
 * A row is two targets — the thumbnail plays, the text beside it opens the details — so this
 * clicks a details zone. The *topmost* one, by position rather than by match order: after a
 * scroll the first match can be a row clipped by the top bar, whose centre falls outside both
 * targets and lands on nothing.
 *
 * Measuring rows on a list that is still settling is a race rather than a mistake — the node
 * found one IPC ago can be gone by the next, which UiAutomator reports as a
 * [androidx.test.uiautomator.StaleObjectException] out of the middle of the journey. Hence the
 * settle wait and the retry: both are cheaper than a generation run that fails at the last
 * step, which is what a bare `visibleBounds` read here cost on both devices.
 */
internal fun MacrobenchmarkScope.openFirstVideoDetails() {
    await(By.res(ROW_DETAILS), TIMEOUT_MS) {
        "No details zone to open — the rows rendered but carry no $ROW_DETAILS tag."
    }
    // The list arrives here still coasting from the fling above, and everything below reads it
    // over IPC one node at a time — see [awaitContentStill] for why waiting is not politeness.
    awaitContentStill()
    repeat(TAP_ATTEMPTS) {
        val rows = device.findObjects(By.res(ROW_DETAILS)).mapNotNull { it.boundsOrGone() }
        // The tallest row on screen is the yardstick for a whole one, because a fixed pixel
        // count only ever fits one device: the same two lines of text measure 96 px on the
        // Pixel Tablet in landscape and half again as much on the Pixel 5, so a threshold
        // generous enough to keep the phone's rows rejected every row the tablet had.
        val whole = rows.maxOfOrNull { it.height() } ?: 0
        val target = rows.filter { it.height() * UNCLIPPED_PARTS >= whole }.minByOrNull { it.top }
        if (target != null) {
            // By coordinate rather than through the node: [UiObject2.click] re-reads the bounds
            // over IPC and throws StaleObjectException if the row has left the tree since it
            // was measured. The point is the same pixel either way.
            device.click(target.centerX(), target.centerY())
            device.waitForIdle()
            return
        }
        // Every candidate went stale mid-measurement, so the list is still moving after all.
        awaitContentStill()
    }
    error(
        "No library row stayed still long enough to tap: every $ROW_DETAILS match was either " +
            "clipped to a fraction of a whole row or left the tree while it was being measured.",
    )
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
internal fun MacrobenchmarkScope.awaitPlaybackUnderway() {
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
    check(
        playing(TIMEOUT_MS),
        diagnose {
            if (JourneyConfig.liveServer) {
                "Playback never got past 0:00. The server may be refusing to stream this " +
                    "item, or the playback mode may have been left on an external player."
            } else {
                "The bundled demo clip never played."
            }
        },
    )
}

/**
 * Whether the player's position label has moved off zero, i.e. the video is really rolling.
 *
 * Absent while the controls are hidden — they go three seconds into playing — which is a false
 * negative the caller answers with a tap, not with a retry loop of its own.
 */
internal fun MacrobenchmarkScope.playing(timeoutMs: Long): Boolean = device.wait(
    Until.hasObject(By.res(PLAYER_POSITION).text(Pattern.compile("(?!^$ZERO_POSITION$).+"))),
    timeoutMs,
)

/** The player's elapsed-position label, and what it reads before anything has played. */
internal const val PLAYER_POSITION = "player_position"

/**
 * Two digits, not one. The label is media3's `PositionText`, which formats through
 * `Util.getStringForTime` — `"%02d:%02d"` below an hour, so zero reads `"00:00"`, not the `"0:00"`
 * our own `formatPosition` produces elsewhere.
 *
 * This is the whole playback leg's tripwire and it fails silently: [playing]'s negative lookahead
 * would *match* `"00:00"` against a stale `"0:00"` here, returning true the instant the label
 * appears and before a byte has streamed. Profiles would still generate, just without playback
 * exercised. The tell is `grep -c Landroidx/media3 baseline-prof.txt` and `grep -c MediaCodec`
 * beside it collapsing — 5,819 and 417 on a profile whose playback leg ran. That says playback
 * *happened*, not that it streamed: a demo run plays a bundled clip and scores the same (5,848 and
 * 431, measured 2026-08-27). Liveness is a different question with a different check — see
 * docs/BASELINE-PROFILE.md, whose okhttp row this used to point at wrongly.
 *
 * Here rather than in either caller because both the generator and the benchmark wait on it, and
 * two copies of a constant whose whole failure mode is silence is exactly one copy too many.
 */
internal const val ZERO_POSITION = "00:00"

internal const val PLAY = "Play"

/** The player's next-video button, by its content description. */
internal const val NEXT_VIDEO = "Next video"

/** The mini-player bar's stop button, by content description — the bar's presence marker. */
internal const val MINI_PLAYER_STOP = "Stop playback"

/**
 * Long enough for a pause, a `clearMediaItems` and the stop report behind them; short enough that a
 * session which is never going to end fails the iteration that caused it rather than the next one.
 */
internal const val PLAYBACK_STOP_TIMEOUT_MS = 10_000L

/** The system permission dialog's grant button, by id so it does not depend on locale. */
internal const val ALLOW_PERMISSION_BUTTON =
    "com.android.permissioncontroller:id/permission_allow_button"

/** A real stream may transcode before the first frame arrives. */
internal const val PLAYBACK_TIMEOUT_MS = 30_000L

/**
 * How much of a whole row has to be showing for its centre to be a safe tap: one part in this many,
 * i.e. half of it. Below that the row is being cut by the top bar, and its centre can fall on the
 * bar rather than on either of the row's two click targets.
 */
internal const val UNCLIPPED_PARTS = 2

/** Tries at finding a row that holds still for long enough to be measured and tapped. */
internal const val TAP_ATTEMPTS = 3
