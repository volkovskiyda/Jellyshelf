package com.gmail.volkovskiyda.jellyshelf.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but never swallows coroutine cancellation — a [CancellationException] is
 * rethrown so structured concurrency keeps working. Use this for any block that calls suspend
 * functions; the stdlib [runCatching] is fine for plain blocking code.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
