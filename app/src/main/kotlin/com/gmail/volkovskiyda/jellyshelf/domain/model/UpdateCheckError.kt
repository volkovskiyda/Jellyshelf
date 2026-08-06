package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * Why an update check or install did not work, in terms a settings screen can render as a sentence.
 *
 * The whole point of this type is that a failure never degrades to a silent "no update available":
 * a user who has switched the check on and sees nothing must be able to tell "you are current"
 * apart from "the API key forbids this call". Each case is a distinct reason with a distinct fix.
 *
 * Only the App Distribution channel produces most of these — GitHub is a plain HTTP GET, so it
 * fails as [Network] or [Unknown] and nothing else.
 */
sealed interface UpdateCheckError {

    /**
     * The Firebase App Testers API is not reachable with this app's API key. The Android key is
     * restricted to an explicit list of APIs, so `firebaseapptesters.googleapis.com` has to be
     * added to it — until then every App Distribution call fails closed, exactly here. See
     * `docs/RELEASING.md`.
     */
    data object ApiDisabled : UpdateCheckError

    /** Signed in, but this account is not a tester on the app — or the invitation is unaccepted. */
    data object NotATester : UpdateCheckError

    /**
     * The user backed out of the sign-in Custom Tab. The ordinary "no thanks" path rather than a
     * fault: it must read as an explanation, not a shout.
     */
    data object SignInCancelled : UpdateCheckError

    /**
     * The channel needs a signed-in tester and there isn't one. Distinct from [SignInCancelled]:
     * nobody was asked. The automatic cold-start check never opens a Custom Tab, so this is what it
     * reports when the token has expired or been revoked since the channel was selected — the fix
     * is to tap "Check now", which is allowed to sign in.
     */
    data object SignInRequired : UpdateCheckError

    /** No usable connection, on either channel. */
    data object Network : UpdateCheckError

    /** The update was found, but downloading its APK failed. */
    data object DownloadFailed : UpdateCheckError

    /** The APK downloaded, but installing it failed. */
    data object InstallFailed : UpdateCheckError

    /** The user declined the system install prompt. Like [SignInCancelled], a choice, not a fault. */
    data object InstallCancelled : UpdateCheckError

    /** The install flow lost the activity it was running over — backgrounded mid-flow. */
    data object Interrupted : UpdateCheckError

    /**
     * Only the API-only stub is linked, so nothing can be detected. Reachable in debug and in the
     * profiling variants, where the Settings section that would show this does not exist — the case
     * is kept anyway because it is what makes the mapping's exhaustive `when` honest.
     */
    data object NotSupported : UpdateCheckError

    /** Anything else, including a failure that is not a Firebase one at all. */
    data object Unknown : UpdateCheckError
}
