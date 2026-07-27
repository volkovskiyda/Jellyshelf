package com.gmail.volkovskiyda.jellyshelf.util

import io.ktor.client.plugins.ResponseException
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

/**
 * What the user is told when the server rejected their token. Deliberately an instruction rather
 * than an error code: the only fix is signing in again, and the app will not quietly fall back to
 * the advanced API key (that would silently restore full-server access the user moved away from).
 */
const val SESSION_EXPIRED_MESSAGE: String = "Session expired — sign in again."

/** Cause chains are shallow; this only exists so a self-referential one can't spin forever. */
private const val MAX_CAUSE_DEPTH = 10

/** HTTP 401: the credential itself was rejected, as opposed to any other 4xx. */
private const val HTTP_UNAUTHORIZED = 401

/**
 * True when the server rejected the credential outright. For a signed-in user this means the token
 * was invalidated (password change, session revoked from the dashboard, server restart with a
 * cleared token store) and only a fresh sign-in can fix it.
 */
fun isUnauthorized(e: Throwable): Boolean =
    (e as? ResponseException)?.response?.status?.value == HTTP_UNAUTHORIZED

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
