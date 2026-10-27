package com.gmail.volkovskiyda.jellyshelf.util

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * A non-fatal report with the context that explains it — the one seam between the app and
 * Crashlytics, as [CloudTraces] is for Firebase Performance.
 *
 * Behind an interface for the same reason: `FirebaseCrashlytics` needs a live `FirebaseApp`, which a
 * JVM test cannot provide, and the decision of *when* to report is the part worth testing.
 */
fun interface CrashReporter {

    /**
     * Records [error] as a non-fatal with [context] attached as its log lines, oldest first.
     *
     * Crashlytics attaches the session's logs to the next event it records, so the lines are written
     * immediately before the exception rather than kept anywhere. Its log buffer is 64 KB, oldest
     * dropped first, which bounds what a long context can carry.
     */
    fun report(error: Throwable, context: List<String>)
}

/**
 * The real thing. Collection is off in debug builds (see `JellyshelfApplication`), where these calls
 * are cheap no-ops; a caller is expected to have logged the same context through Timber.
 */
internal class FirebaseCrashReporter : CrashReporter {
    override fun report(error: Throwable, context: List<String>) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        context.forEach(crashlytics::log)
        crashlytics.recordException(error)
    }
}
