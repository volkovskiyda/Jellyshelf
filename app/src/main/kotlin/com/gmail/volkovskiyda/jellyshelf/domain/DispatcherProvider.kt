package com.gmail.volkovskiyda.jellyshelf.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Single source of coroutine dispatchers and app-lifetime scopes, injected everywhere instead of
 * referencing `Dispatchers.*` directly — so a test can substitute one deterministic dispatcher for
 * all of them, and the "supervised scope that logs its failures" pattern lives in one place.
 *
 * ViewModels are intentionally not covered here: their `viewModelScope` is created per-instance by
 * the `ViewModel` base class and cancelled when the ViewModel clears, so it can't come from a
 * shared singleton.
 */
interface DispatcherProvider {
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher

    /** IO restricted to a single worker, for strictly-ordered writes (e.g. scroll persistence). */
    val ioSequential: CoroutineDispatcher

    /** Process-lifetime `SupervisorJob` scope on [default] for best-effort app-wide work. */
    val applicationScope: CoroutineScope

    /**
     * A fresh `SupervisorJob` scope on [io] whose uncaught child failures are logged under [tag]
     * with [failureMessage]: best-effort background work (bulk fetch, playback reports, scroll
     * persistence) must never crash the process on an unexpected failure.
     */
    fun ioScope(tag: String, failureMessage: String = "background work failed"): CoroutineScope
}
