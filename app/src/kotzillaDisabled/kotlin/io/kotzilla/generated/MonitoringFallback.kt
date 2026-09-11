package io.kotzilla.generated

import org.koin.core.KoinApplication

/**
 * Stands in for the `monitoring()` that Kotzilla's Gradle plugin generates into this package, and is
 * compiled only when `app/kotzilla.json` is absent — see the `kotzilla { }` block and the source-set
 * line that adds this directory in `app/build.gradle.kts`.
 *
 * The plugin generates nothing at all when it is disabled (verified against 2.3.6: no file, not an
 * empty one), so without this the `monitoring()` call in `JellyshelfApplication` would not resolve
 * and a checkout with no API keys — a fresh clone, a fork's pull request, CI's build and test jobs —
 * could not compile. Keeping the call site unconditional is the point: the app reads the same in
 * both cases, and nothing has to be remembered when the keys are present.
 *
 * Deliberately references no Kotzilla type. The plugin also adds the SDK runtime, so when it is off
 * there is no `KotzillaCore` to name; only Koin's own [KoinApplication], which the app always has.
 * The real extension takes a logger and a config lambda, both optional — this matches the call this
 * project actually makes, and a future call that passes either argument should fail here loudly
 * rather than be silently ignored.
 */
@Suppress("UnusedReceiverParameter")
internal fun KoinApplication.monitoring(): Unit = Unit
