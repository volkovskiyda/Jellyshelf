package com.gmail.volkovskiyda.jellyshelf.data.remote

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.gmail.volkovskiyda.jellyshelf.util.ActivityTracker
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLEncoder

/**
 * The page the SDK opens, lifted from `TesterSignInManager.SIGNIN_REDIRECT_URL`. `%s` in order:
 * Firebase app id, installation id, app name, package name.
 */
private const val SIGN_IN_URL =
    "https://appdistribution.firebase.google.com/pub/testerapps/%s/installations/%s/" +
        "buildalerts?appName=%s&packageName=%s&newRedirectScheme=true"

/**
 * Where that page redirects back to. Owned by the SDK and present only in release builds, so it is
 * watched for by name rather than by class.
 */
private const val SIGN_IN_RESULT_ACTIVITY =
    "com.google.firebase.appdistribution.impl.SignInResultActivity"

/**
 * The two halves of an out-of-app sign-in: send the user off, and notice them coming back.
 *
 * An interface because the implementation is unreachable off a device — it needs a `Context`, a
 * foreground `Activity` and a browser — while everything that *decides* what to do with its answers
 * lives in [AppDistributionSource] and is worth testing on the JVM.
 */
interface TesterSignIn {

    /**
     * Opens the sign-in page, or answers `false` when it cannot — which is a
     * fall-back-to-the-SDK answer, not a failure to report.
     */
    suspend fun start(): Boolean

    /** Suspends until the user is back in the app, whether they signed in or backed out. */
    suspend fun awaitReturn()
}

/**
 * Opens the tester sign-in in a Custom Tab **in this app's task**, which the SDK's own sign-in
 * will not do.
 *
 * ## Why this exists
 *
 * `FirebaseAppDistribution.signInTester()` builds a `CustomTabsIntent` and adds
 * `FLAG_ACTIVITY_NEW_TASK` before launching it (verified by disassembling `TesterSignInManager`:
 * `addFlags(268435456)` on both the Custom Tab and the plain-browser fallback). The tab therefore
 * becomes a task of the browser's own, which means it lingers as a separate card in Recents, the
 * platform's back-out-of-a-root-activity behaviour returns the user to it, and **no API lets this
 * app close it** — an app cannot finish an activity in another app's task.
 *
 * Launching the same URL ourselves from an `Activity`, without that flag, puts the tab in our task.
 * That makes it ours to close, which [awaitReturn] does the moment the redirect lands.
 *
 * ## Why it is safe to bypass the SDK here
 *
 * The SDK records the sign-in from a lifecycle hook, not from its own bookkeeping:
 * `TesterSignInManager.onActivityCreated` tests `activity instanceof SignInResultActivity` and
 * calls `SignInStorage.setSignInStatus(true)` unconditionally — it never checks that it started the
 * flow. So a redirect we caused marks the tester signed in exactly as one the SDK caused would, and
 * `isTesterSignedIn()` and `checkForNewRelease()` carry on unaware.
 *
 * ## What happens when that stops being true
 *
 * Both facts above are **undocumented internals of a beta SDK** (16.0.0-beta20, the newest
 * published). [start] answers `false` for every failure it can actually detect — no foreground
 * activity, no installation id, no browser that does Custom Tabs, a launch that throws — and the
 * caller then falls back to `signInTester()`, so a build where this cannot work degrades to the
 * old behaviour rather than to no sign-in at all.
 *
 * What it cannot detect is the URL quietly changing shape: the tab would open on an error page and
 * the user would come back not signed in, which reads as a cancelled sign-in. That is deliberate —
 * the alternative is throwing a second browser window at someone who simply chose to back out.
 */
class TesterSignInLauncher(
    private val context: Context,
    private val activities: ActivityTracker,
) : TesterSignIn {

    /**
     * Opens the Custom Tab, or answers `false` when it cannot — in which case the caller must fall
     * back to the SDK rather than report a failure.
     */
    override suspend fun start(): Boolean {
        val activity = activities.current() ?: return false
        if (CustomTabsClient.getPackageName(context, emptyList()) == null) return false
        val url = signInUrl() ?: return false
        return try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(activity, url.toUri())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // ActivityNotFoundException, or a security failure from a browser that has since been
            // disabled. Either way this launcher cannot run, which is what `false` means.
            Timber.w(e, "Could not open the tester sign-in tab")
            false
        }
    }

    /**
     * Suspends until the user is back in the app, closing the Custom Tab if the redirect fires.
     *
     * The close is the whole point of owning the launch. `SignInResultActivity` finishes itself,
     * which would leave the tab — now the top of *our* task — showing a redirect page the user has
     * no reason to see. Relaunching through the launcher intent with `CLEAR_TOP` finishes
     * everything above the main activity, tab included.
     *
     * Resolved by a resume either way, so backing out of the tab without signing in returns here
     * just the same.
     */
    override suspend fun awaitReturn() {
        val mark = activities.resumes.value
        coroutineScope {
            val closer = launch {
                activities.created.first { it == SIGN_IN_RESULT_ACTIVITY }
                closeTab()
            }
            activities.resumes.first { it > mark }
            closer.cancel()
        }
    }

    private fun closeTab() {
        // By launcher intent rather than by class: this layer has no business naming MainActivity,
        // and the manifest already answers "which activity is the app".
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            Timber.w("No launcher intent; leaving the sign-in tab open")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    /** Null when anything it is built from is missing, which is a fall-back-to-the-SDK answer. */
    private suspend fun signInUrl(): String? {
        val appId = FirebaseApp.getInstance().options.applicationId
        if (appId.isEmpty()) return null
        val installationId = try {
            FirebaseInstallations.getInstance().id.await()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "No installation id for the tester sign-in URL")
            null
        }
        if (installationId.isNullOrEmpty()) return null
        return SIGN_IN_URL.format(
            encode(appId),
            encode(installationId),
            encode(appName()),
            encode(context.packageName),
        )
    }

    private fun appName(): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
