package com.gmail.volkovskiyda.jellyshelf.domain

/**
 * The wall clock, injected everywhere instead of calling `System.currentTimeMillis()` directly — so
 * a test can decide what time it is rather than sleeping until the real clock agrees.
 *
 * Named for what it provides rather than what it stores, alongside [DispatcherProvider]: nothing is
 * fetched or persisted here, so this is not a repository however much it is injected like one.
 *
 * Milliseconds since the Unix epoch, which is what every stored timestamp in this app already is —
 * the sync markers, the watch stamps and the three update windows all compare against values that
 * came from this clock. It is therefore *wall* time and can jump backwards when the user or the
 * network corrects the device clock; nothing here is suitable for measuring a duration.
 */
fun interface TimeProvider {
    /** Milliseconds since the Unix epoch. */
    fun now(): Long
}
