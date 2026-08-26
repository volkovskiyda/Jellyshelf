package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
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
 * Run it by hand with a device attached, awake and unlocked — it is not part of any build:
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
 * The run inherits whatever state the installed app is in: [ensureLibrary] detects it from the
 * screen rather than assuming a fresh install, and a run against an already-synced install simply
 * profiles less of the connect path. For a true first-use profile, clear the app's data before
 * generating — the runbook says how.
 *
 * The steps that reach a populated library, and the selectors they steer by, live in
 * [LibraryJourney.kt][JourneyConfig] beside this file, because [JourneyBenchmark] needs the same
 * state before it can measure anything and cannot get there any other way. What stays here is what
 * only a profile wants: the browse, the detail screen and playback.
 */
@RunWith(AndroidJUnit4::class)
// The three tests are one sequence — connect, then use, then measure a launch into what that left
// behind — so they are numbered rather than named in a way that happens to sort right.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val targetPackage = JourneyConfig.targetPackage

    /**
     * Getting a library on screen: sign in and sync a real one, or seed the demo. Everything the
     * app does once rather than on every launch is in [ensureLibrary] — the sign-in exchange, the
     * first sync, the folder browser — and none of it belongs in the startup profile.
     *
     * Iterations 2+ are cheap for free: the session and the synced library survive the process kill
     * between them, so each step's on-screen guard sends it straight past, and this test then does
     * little more than confirm the library is still there.
     */
    @Test
    fun generate1Connect() = rule.collect(packageName = targetPackage) {
        ensureLibrary()
    }

    /**
     * The journey, and the bulk of the profile: the library list and its scroll, a video's detail
     * screen, and playback. Deliberately **not** in the startup profile — that one is stored in the
     * primary dex to be read on every cold start, so putting a video player and two list screens in
     * it would cost startup rather than buy it.
     *
     * Relies on [generate1Connect] having left a populated library, which `@FixMethodOrder`
     * guarantees — and calls [ensureLibrary] itself anyway, so a run that starts here on an install
     * that never saw that test still has something to browse.
     */
    @Test
    fun generate2Journey() = rule.collect(packageName = targetPackage) {
        ensureLibrary()
        repeat(SCROLLS) { flingScreen(Direction.DOWN) }
        flingScreen(Direction.UP)

        // The first video every time, so successive profiles cover the same item rather than
        // whichever row a scroll happened to leave under the selector.
        openFirstVideoDetails()

        // Playback pulls in Media3/ExoPlayer, a large slice of first-use class loading the launch
        // path alone would miss — and against a real server it is the streaming path (HTTP data
        // source, container parsing, codec setup) rather than a bundled file read.
        // Scrolled to rather than merely looked for: the detail screen is one long scrolling
        // column headed by a thumbnail that fills the width, so on a landscape tablet the poster
        // alone is most of the viewport and the button starts below the fold. Every other control
        // this journey reaches inside a scrolling screen is found the same way, and the failure
        // names what is on screen instead of only what is missing.
        scrollTo(By.text(PLAY)) { "The detail screen never offered playback." }
        // Found, then found again after the screen holds still, because locating it is not the
        // same as being able to hit it: the thumbnail above the button loads while this screen is
        // being read and the layout it settles into moves everything below it — 160 px on the
        // tablet, measured — so a tap aimed at where the button was lands on nothing at all, and
        // the run then waits out the whole playback timeout in front of a Play button it can see.
        awaitContentStill()
        await(By.text(PLAY), TIMEOUT_MS) {
            "The Play button left the detail screen while it was being reached for."
        }.click()
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
}
