package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.MainActivity
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
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
    )

    private val overrides = module { single { checker } }

    /** The app's own checker, put back in [restoreTheRealChecker]. */
    private lateinit var realChecker: UpdateChecker

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Re-declares the real single rather than unloading the override.
     *
     * `unloadKoinModules` removes definitions *by key*, so unloading a module that declares an
     * `UpdateChecker` takes the app's own definition with it and leaves the graph without one for
     * the rest of the process — every later test that builds a screen then dies on
     * `NoDefinitionFoundException`. Overwriting the binding back is the only way to undo an
     * override without taking the original with it.
     */
    @After
    fun restoreTheRealChecker() {
        loadKoinModules(module { single { realChecker } })
    }

    private fun label(resId: Int, vararg args: Any) = composeRule.activity.getString(resId, *args)

    private val offerTitle get() = label(R.string.update_available_title, OFFERED_VERSION_NAME)

    /**
     * Loaded before the activity is touched — the activity resolves the checker during composition,
     * so an override installed here is the one it sees.
     */
    private fun installOverride() {
        realChecker = GlobalContext.get().get()
        loadKoinModules(overrides)
        composeRule.waitForIdle()
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
        installOverride()
        switchToTab(R.string.tab_settings)

        runBlocking { checker.checkNow() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(offerTitle).assertIsDisplayed()
    }

    /**
     * And an offer nobody asked for still waits, or the tab rule would be dead code — this is the
     * negative that gives the test above its meaning.
     */
    @Test
    fun anAutomaticOffer_waitsWhileAnotherTabIsOpen() {
        installOverride()
        switchToTab(R.string.tab_settings)

        checker.checkOnStart()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(offerTitle).assertDoesNotExist()
    }

    /** The same automatic offer, once the user is back where it is allowed to interrupt. */
    @Test
    fun anAutomaticOffer_arrivesOnTheLibraryTab() {
        installOverride()
        switchToTab(R.string.tab_settings)
        checker.checkOnStart()
        composeRule.waitForIdle()

        switchToTab(R.string.tab_library)

        composeRule.onNodeWithText(offerTitle).assertIsDisplayed()
    }
}
