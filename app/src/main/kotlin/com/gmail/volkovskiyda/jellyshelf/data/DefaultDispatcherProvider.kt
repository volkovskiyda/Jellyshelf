package com.gmail.volkovskiyda.jellyshelf.data

import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Production [DispatcherProvider]. The three base dispatchers are constructor parameters defaulting
 * to the real ones, so a test can pass a single `TestDispatcher` for all three and drive every
 * scope deterministically.
 */
class DefaultDispatcherProvider(
    override val main: CoroutineDispatcher = Dispatchers.Main,
    override val default: CoroutineDispatcher = Dispatchers.Default,
    override val io: CoroutineDispatcher = Dispatchers.IO,
) : DispatcherProvider {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val ioSequential: CoroutineDispatcher = io.limitedParallelism(1)

    override val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + default)

    override fun ioScope(tag: String, failureMessage: String): CoroutineScope = CoroutineScope(
        SupervisorJob() + io +
            CoroutineExceptionHandler { _, e -> Timber.tag(tag).w(e, failureMessage) },
    )
}
