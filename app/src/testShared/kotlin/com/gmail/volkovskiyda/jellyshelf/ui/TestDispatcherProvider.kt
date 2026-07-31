package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One dispatcher for everything, which is what [DispatcherProvider] exists to make possible.
 *
 * Defaults to [Dispatchers.Unconfined] so background work in a constructor's `init` has run by the
 * time the constructor returns — the common need in a state-holder test. Pass a
 * `StandardTestDispatcher` instead when a test wants to control *when* that work runs.
 */
class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val ioSequential: CoroutineDispatcher = dispatcher
    override val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun ioScope(tag: String, failureMessage: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
