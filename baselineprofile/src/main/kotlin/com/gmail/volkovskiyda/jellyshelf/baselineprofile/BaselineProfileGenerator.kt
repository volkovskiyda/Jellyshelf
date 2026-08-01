package com.gmail.volkovskiyda.jellyshelf.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        // A row opens the detail screen: image loading and the metadata section.
        await(By.res(LIBRARY_ROW), TIMEOUT_MS) {
            "No library row to open — the list rendered but its rows carry no test tag."
        }.click()

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

        /** Visible text, for the controls with no tag of their own. */
        const val TRY_DEMO = "Try demo"
        const val PLAY = "Play"
        const val TAB_LIBRARY = "Library"
        const val TAB_CATEGORIES = "Categories"

        const val TIMEOUT_MS = 5_000L

        /** Generous: seeding writes ~60 videos and their categories to a real database. */
        const val SEED_TIMEOUT_MS = 30_000L

        const val SCROLLS = 3
        const val GESTURE_MARGIN_FRACTION = 5

        /** Deep enough for player → detail → library, with room to spare. */
        const val MAX_BACK_PRESSES = 5
    }
}
