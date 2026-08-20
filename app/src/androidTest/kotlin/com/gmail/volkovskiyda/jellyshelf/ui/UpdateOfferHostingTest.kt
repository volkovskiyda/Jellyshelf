package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.MainActivity
import com.gmail.volkovskiyda.jellyshelf.NotificationPermissionRule
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

/** Above the fake installed build, so every offer here is genuinely newer. */
private const val INSTALLED_VERSION_CODE = 100
private const val OFFERED_VERSION_CODE = 200
private const val OFFERED_VERSION_NAME = "1.0.200"

/**
 * A plausible instant, and it has to be one: every stored timestamp starts at `0` meaning "never",
 * and the windows read that as elapsed only because `now` is enormous beside it. A clock frozen at
 * `0` makes `now - 0` zero, so the one-a-day dialog floor suppresses the *automatic* offer and the
 * negative test below passes for having nothing to show rather than for the rule it names.
 */
private const val NOW = 1_800_000_000_000L

/**
 * How long the offer dialog is given to arrive.
 *
 * It gets any at all because the dialog is a *window*: `waitForIdle` returns once the composition
 * that asked for it has settled, which is a beat before that window is attached and laid out. The
 * bare assertion this replaces could not tell the two apart either way — `assertIsDisplayed` fetches
 * nodes in the plural, so a node that is missing and one that is merely not laid out yet both come
 * back as the same "is not displayed".
 */
private const val OFFER_TIMEOUT_MS = 5_000L

/**
 * Where an update offer is allowed to appear, through the real [MainActivity] wiring.
 *
 * This is the one place the update feature is exercised end to end on a device, and it needs a DI
 * override to exist at all: instrumented tests run the **debug** variant, where the checker returns
 * before doing anything, the Settings section is hidden, and the App Distribution SDK is the
 * API-only stub. Replacing the [UpdateChecker] single with one built on a release-shaped
 * [BuildInfo] and a stub GitHub source lifts exactly those three gates and nothing else — the
 * activity, the navigation and the dialog are all the real ones.
 *
 * The rule under test is asymmetric on purpose. An offer nobody asked for interrupts, so it waits
 * for the Library tab; one the user asked for is the answer to a question they just posed, and
 * appears wherever they posed it. [UpdateCheckerTest][com.gmail.volkovskiyda.jellyshelf.domain.UpdateCheckerTest]
 * covers which offer gets which provenance; this covers what the host does with it.
 *
 * `JellyshelfApplication.onCreate` resolves the real checker before any of this loads, so the
 * cold-start check has already run (and returned at its debug gate) against a different instance.
 *
 * Instrumented, per this project's no-Robolectric convention.
 */
@RunWith(AndroidJUnit4::class)
class UpdateOfferHostingTest {

    private val settings = FakeSettingsRepository(updateSource = UpdateSource.GITHUB)

    private val gitHub = object : GitHubReleaseSource(
        // Never asked for anything: latestRelease is overridden outright.
        HttpClient(OkHttp),
        TestDispatcherProvider(),
        Json,
    ) {
        override suspend fun latestRelease() = UpdateInfo(
            versionCode = OFFERED_VERSION_CODE,
            versionName = OFFERED_VERSION_NAME,
            releaseNotes = "",
            downloadUrl = "https://example.invalid/jellyshelf-$OFFERED_VERSION_NAME.apk",
            source = UpdateSource.GITHUB,
        )
    }

    private val checker = UpdateChecker(
        settingsRepository = settings,
        gitHubSource = gitHub,
        appDistributionSource = AppDistributionSource(InertTesterSignIn, FakeUpdateFlags()),
        buildInfo = BuildInfo(isDebug = false, sdkInt = 36, versionCode = INSTALLED_VERSION_CODE),
        time = TimeProvider { NOW },
        dispatchers = TestDispatcherProvider(),
        updateCheckSchedule = RecordingUpdateCheckSchedule(),
    )

    private val overrides = module { single { checker } }

    /** The app's own checker, put back by [koinOverride]. */
    private lateinit var realChecker: UpdateChecker

    /**
     * Granted before the activity exists: the library screen asks for it as soon as it has videos,
     * and residue from an earlier test in this suite is enough to make that happen — over the
     * offer this test is looking for, in a window of its own.
     */
    @get:Rule(order = 0)
    val notificationPermission = NotificationPermissionRule()

    /**
     * Swaps the checker in **before the compose rule launches the activity**, and puts the real one
     * back afterwards. Both halves have to be a rule rather than test-body calls.
     *
     * `koinInject` resolves once and remembers, so whichever checker the composition sees first is
     * the one it keeps for the life of the screen. Loading the override from the test body is a
     * race against `startStack` resolving out of DataStore — win it and the screen watches the fake,
     * lose it and the screen watches the app's own checker, which has nothing to offer and never
     * shows a dialog. That is the whole of the tablet failure this rule was written for on Test
     * Lab; on a device that reads DataStore a few hundred ms slower the same test passes for no
     * better reason than luck.
     *
     * Restoring re-declares the real single rather than unloading the override: `unloadKoinModules`
     * removes definitions *by key*, so unloading a module that declares an `UpdateChecker` takes the
     * app's own definition with it and leaves the graph without one for the rest of the process —
     * every later test that builds a screen then dies on `NoDefinitionFoundException`.
     */
    @get:Rule(order = 1)
    val koinOverride = object : ExternalResource() {
        override fun before() {
            realChecker = GlobalContext.get().get()
            loadKoinModules(overrides)
        }

        override fun after() {
            loadKoinModules(module { single { realChecker } })
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun label(resId: Int, vararg args: Any) = composeRule.activity.getString(resId, *args)

    private val offerTitle get() = label(R.string.update_available_title, OFFERED_VERSION_NAME)

    /** See [OFFER_TIMEOUT_MS] — the dialog's window lands a frame or two after the composition. */
    private fun awaitOffer() {
        composeRule.waitUntil(OFFER_TIMEOUT_MS) {
            composeRule.onNodeWithText(offerTitle).isDisplayed()
        }
    }

    /**
     * By click action, not by text alone: each tab's label is also the heading of the screen it
     * opens, so "Settings" matches two nodes and only one of them is tappable.
     */
    private fun switchToTab(resId: Int) {
        composeRule.onNode(hasText(label(resId)) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    /** What "Check now" is for: the answer arrives on the screen the question was asked from. */
    @Test
    fun aRequestedOffer_showsOnTheSettingsTab() {
        switchToTab(R.string.tab_settings)

        runBlocking { checker.checkNow() }
        composeRule.waitForIdle()

        awaitOffer()
        composeRule.onNodeWithText(offerTitle).assertIsDisplayed()
    }

    /**
     * And an offer nobody asked for still waits, or the tab rule would be dead code — this is the
     * negative that gives the test above its meaning.
     */
    @Test
    fun anAutomaticOffer_waitsWhileAnotherTabIsOpen() {
        switchToTab(R.string.tab_settings)

        checker.checkOnStart()
        composeRule.waitForIdle()

        // There is an offer to suppress: without this the test passes on a checker that found
        // nothing, which is every way the feature could break rather than the rule it names.
        assertNotNull(checker.available.value)
        composeRule.onNodeWithText(offerTitle).assertDoesNotExist()
    }

    /** The same automatic offer, once the user is back where it is allowed to interrupt. */
    @Test
    fun anAutomaticOffer_arrivesOnTheLibraryTab() {
        switchToTab(R.string.tab_settings)
        checker.checkOnStart()
        composeRule.waitForIdle()

        switchToTab(R.string.tab_library)

        awaitOffer()
        composeRule.onNodeWithText(offerTitle).assertIsDisplayed()
    }
}
