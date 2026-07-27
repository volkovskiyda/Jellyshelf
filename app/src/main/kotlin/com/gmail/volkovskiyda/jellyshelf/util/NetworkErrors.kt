package com.gmail.volkovskiyda.jellyshelf.util

import java.net.UnknownServiceException

/**
 * What the user is told when the platform blocked a plain-HTTP request.
 *
 * Not a string resource because the data layer needs it too — `DefaultLibraryRepository.sync()`
 * has no `Context`, and its `SyncResult.Error` messages are already plain English for the same
 * reason. Keeping one definition beats a resource plus a constant drifting apart. The app ships
 * no translations, so nothing is lost.
 */
const val CLEARTEXT_BLOCKED_MESSAGE: String =
    "Release builds only allow https:// — enable HTTPS on the server, or install a debug build " +
        "to use a plain-HTTP address."

/** Cause chains are shallow; this only exists so a self-referential one can't spin forever. */
private const val MAX_CAUSE_DEPTH = 10

/**
 * True when a request failed because Android's network security policy rejected cleartext.
 *
 * Release builds ship without `usesCleartextTraffic` (only the debug manifest overlay sets it),
 * so an `http://` server or index URL fails before it ever reaches the network — with a
 * "CLEARTEXT communication to … not permitted" message that says nothing about how to fix it.
 * Callers swap in [CLEARTEXT_BLOCKED_MESSAGE] instead.
 *
 * The check walks the cause chain: the failure surfaces from inside OkHttp, so Ktor hands it up
 * wrapped.
 */
fun isCleartextBlocked(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        if (cause is UnknownServiceException && cause.message?.contains("CLEARTEXT") == true) {
            return true
        }
        cause = cause.cause.takeIf { it !== cause }
    }
    return false
}
