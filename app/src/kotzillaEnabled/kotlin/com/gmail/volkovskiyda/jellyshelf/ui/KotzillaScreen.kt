package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.navigation.routeArgs
import com.gmail.volkovskiyda.jellyshelf.navigation.screenName
import io.kotzilla.sdk.compose.KotzillaScreenHost

/**
 * Registers whatever it wraps as one Kotzilla screen visit, for as long as it is composed.
 *
 * **Why this is hand-written.** Kotzilla's Gradle plugin carries a Kotlin compiler plugin that
 * injects exactly this call for you — but only into the content lambda of an
 * `androidx.navigation3.runtime.EntryProviderScope.entry` call, the `entryProvider { entry<Key> { } }`
 * DSL. This app builds its `NavEntry`s directly, inside one `when (key)` in `MainActivity`, because
 * every entry also has to carry its own bottom-chrome reservation. Nothing matches, so nothing was
 * injected, and the console listed one screen — `MainActivity` — for the whole app. Verified
 * against SDK 2.3.6 by recompiling and finding no `io.kotzilla.sdk.compose` reference anywhere in
 * the app's classes; `kotzillaVerifyComposeInstrumentation` passes regardless, because it checks
 * that the compiler plugin is *wired*, not that it found anything to rewrite.
 *
 * So the names match what the plugin would have produced, deliberately: [screenName] is the key's
 * simple class name, which is how the SDK names a Navigation 3 destination, and the screen type is
 * the one the plugin passes. The SDK's own `extractNav3ScreenName` is `internal` and cannot be
 * called from here. If a later SDK learns to rewrite a bare `NavEntry`,
 * every visit here would be reported twice — so after any Kotzilla bump, check the compiled classes
 * for an injected `KotzillaScreenHost` before trusting the numbers.
 *
 * Wrapping rather than merely observing: the host measures time to first draw and visibility from
 * its own layout node, which is the number this exists to collect.
 */
@Composable
internal fun KotzillaScreen(key: AppNavKey, content: @Composable () -> Unit) {
    val name = key.screenName()
    KotzillaScreenHost(
        name = name,
        screenType = SCREEN_TYPE_NAV3,
        // What the compiler plugin counts in a route body it rewrote. Zero is the honest answer
        // from a hand-written host, and the console treats both as informational.
        rememberCount = 0,
        effectCount = 0,
        simpleName = name,
        // The SDK instance is left at its default, which resolves the running one per call — a
        // keyless build has none, and this file is not compiled there anyway.
        routeArgsProvider = { key.routeArgs() },
        deepLinkPattern = "",
        content = content,
    )
}

/** The wire name Kotzilla files a Navigation 3 destination under. Not an enum in its public API. */
private const val SCREEN_TYPE_NAV3 = "compose_nav3"
