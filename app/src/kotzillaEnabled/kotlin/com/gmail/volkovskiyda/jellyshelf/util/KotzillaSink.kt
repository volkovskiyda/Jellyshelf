package com.gmail.volkovskiyda.jellyshelf.util

import io.kotzilla.sdk.KotzillaCore

/**
 * The real [MetricsSink], compiled only when `app/kotzilla.json` is present.
 *
 * Its twin in `src/kotzillaDisabled` stands in when the file is absent, and the two must stay
 * signature-identical — see that file for why there are two at all. `app/build.gradle.kts` adds
 * exactly one of the two directories to the main source set, never both.
 *
 * Every call resolves the SDK instance afresh. It is not a value worth caching: the SDK boots from
 * its own `Initializer` before `Application.onCreate` runs, so an instance captured at construction
 * time could be null purely by accident of ordering, and `close()` replaces it. A null instance
 * means no session is open, and every method here is then a no-op — which is also what a keyless
 * build does, so the two shapes behave alike.
 */
internal object KotzillaSink : MetricsSink {

    /**
     * The second argument is the SDK's stack-capture flag: true makes it walk the calling thread's
     * stack and keep the top frames beside the trace. Off here for every span this app measures —
     * they include the per-emission library paths, which is the last place to pay for a stack walk.
     * Passed positionally because the SDK's own parameter name is not part of its published API.
     */
    override suspend fun <T> suspendTrace(id: String, block: suspend () -> T): T {
        val core = KotzillaCore.getDefaultInstanceOrNull() ?: return block()
        // Boxed because the SDK's trace is declared over a non-null type, and [MetricsSink]
        // deliberately is not — a span whose block returns null is still a span.
        return core.suspendTrace(id, false) { Boxed(block()) }.value
    }

    /** Carries a possibly-null result through the SDK's non-null type parameter. */
    private class Boxed<T>(val value: T)

    override fun mark(label: String, track: String) {
        KotzillaCore.getDefaultInstanceOrNull()?.mark(label, track)
    }

    override fun log(message: String) {
        KotzillaCore.getDefaultInstanceOrNull()?.log(message)
    }
}
