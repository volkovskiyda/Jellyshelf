package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateCheckFailure
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How often the app may *ask* a channel anything. Network politeness, not a nag limit. */
private val CHECK_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1)

/** How often the app may *interrupt* with a dialog, whatever it found and on whichever channel. */
private val DIALOG_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1)

/** How long "Not now" silences that same build. A dismissal is a snooze; it expires. */
private val SNOOZE_MILLIS = TimeUnit.DAYS.toMillis(7)

/**
 * Whether [window] has passed since [since].
 *
 * `0` means "never" for all three stored timestamps, and `now - 0` is enormous, so a fresh install
 * passes every window rather than being muted for its first day.
 *
 * `internal` rather than `private` only so the class below reaches it without a synthetic accessor.
 */
internal fun elapsed(now: Long, since: Long, window: Long): Boolean = now - since >= window

/**
 * Decides whether to offer the user a newer build, and holds the answer for the UI.
 *
 * A Koin `single` rather than a ViewModel because the check runs once per process at cold start and
 * its result outlives any one screen — a tab ViewModel is cleared on every tab switch.
 * [AppSettingsState] is the same shape for the same reason.
 *
 * ## What gates what
 *
 * Two kinds of gate, and the difference decides which of them "Check now" may skip:
 *
 * - **Validity** — debug build, no real version code, no channel selected. This build cannot
 *   meaningfully compare itself to anything, so *both* paths stop here. A debug install is
 *   `…jellyshelf.debug` with `versionCode = 1` and a `-debug` version name, so every published
 *   release would read as newer; a locally assembled release without `-PbuildNumber` reports `1`
 *   and does the same.
 * - **Politeness** — the check interval, the dialog floor and the dismissal snooze. These are
 *   about not being a pest, so a user who explicitly taps "Check now" is not subject to them.
 *
 * The `NONE` default is the *only* thing keeping the `nonMinifiedRelease` / `benchmarkRelease`
 * profiling variants from checking during a baseline-profile run: those report `isDebug = false`,
 * carry a real version code, and (measured, not assumed) link the full App Distribution SDK just as
 * `release` does. Nothing else about them is different, so do not weaken that default.
 *
 * ## When a dismissed version comes back
 *
 * Three stored timestamps, all `0` == "never" == "the window has elapsed":
 *
 * | Key | Window | Meaning |
 * |---|---|---|
 * | `last_update_check_at` | 1 day | how often we ask |
 * | `last_update_dialog_at` | 1 day | how often we interrupt |
 * | `dismissed_update_at_<source>` | 7 days | how long "Not now" silences *that* build |
 *
 * So: install 1.0, get offered 1.1, dismiss it, and 1.2 ships — the next daily check offers 1.2,
 * because it is newer than anything dismissed. If 1.2 never ships, 1.1 returns on day 7. A build
 * *older* than the dismissed one takes the same path as the same one and waits the snooze out.
 */
class UpdateChecker(
    private val settingsRepository: SettingsRepository,
    private val gitHubSource: GitHubReleaseSource,
    private val appDistributionSource: AppDistributionSource,
    private val buildInfo: BuildInfo,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
) {
    // MutableStateFlow, never @Volatile or an atomic: the project has carried zero @Volatile since
    // 2026-07-29 and this is not the place to reintroduce one.
    private val _available = MutableStateFlow<UpdateInfo?>(null)

    /** The update to offer, or null. Null until a check finds something — most launches. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _error = MutableStateFlow<UpdateCheckError?>(null)

    /** Why the last check failed, for Settings to render. Cleared when a check starts. */
    val error: StateFlow<UpdateCheckError?> = _error.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _signingIn = MutableStateFlow(false)

    /** A tester sign-in is in flight, from [selectSource]. The Custom Tab is a separate task. */
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    /** Whether the whole feature should be hidden from the UI — see [checkOnStart]'s first gate. */
    val isDebugBuild: Boolean = buildInfo.isDebug

    /**
     * The cold-start check. Fire-and-forget on the application scope: nothing waits for it, and a
     * launch must not be delayed by a network call.
     *
     * **Never signs a tester in.** `signInTester()` opens a Custom Tab, and throwing a browser over
     * the app during launch is not an acceptable way to ask. An `APP_DISTRIBUTION` user who is not
     * signed in gets [UpdateCheckError.SignInRequired] instead, which Settings renders as a prompt
     * to use "Check now" — the one path allowed to sign in.
     */
    fun checkOnStart() {
        dispatchers.applicationScope.launch { check(manual = false) }
    }

    /**
     * The "Check now" path: skips every politeness window and may sign a tester in, because the
     * user asked. Suspends so the caller's scope owns it and the button can show progress.
     */
    suspend fun checkNow() = check(manual = true)

    private suspend fun check(manual: Boolean) {
        if (buildInfo.isDebug || buildInfo.versionCode <= 1) return
        val source = settingsRepository.updateSource.first()
        if (source == UpdateSource.NONE) return
        if (!manual &&
            !elapsed(time.now(), settingsRepository.lastUpdateCheckAt.first(), CHECK_INTERVAL_MILLIS)
        ) {
            return
        }

        _error.value = null
        _checking.value = true
        try {
            val info = fetch(source, manual)
            // Stamped even when nothing was found, and *not* stamped on failure: the interval is
            // about how often we ask, so a transient outage must not buy a day of silence.
            settingsRepository.setLastUpdateCheckAt(time.now())
            _available.value = info?.takeIf { manual || shouldOffer(it) }
        } catch (e: UpdateCheckFailure) {
            _error.value = e.reason ?: UpdateCheckError.Unknown
        } catch (e: IOException) {
            Timber.w(e, "Update check could not reach $source")
            _error.value = UpdateCheckError.Network
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A non-2xx from GitHub arrives as a Ktor ResponseException, which is neither of the
            // above. Broad on purpose: a check is best-effort background work and must never take
            // the process down, and every reason still reaches the user as a rendered sentence.
            Timber.w(e, "Update check against $source failed")
            _error.value = UpdateCheckError.Unknown
        } finally {
            _checking.value = false
        }
    }

    private suspend fun fetch(source: UpdateSource, manual: Boolean): UpdateInfo? = when (source) {
        UpdateSource.NONE -> null
        UpdateSource.GITHUB -> gitHubSource.latestRelease()
        UpdateSource.APP_DISTRIBUTION -> {
            if (!appDistributionSource.isTesterSignedIn()) {
                // Selecting this channel required a successful sign-in, so reaching here means the
                // token was revoked or expired since. Real, but no longer the common path.
                if (!manual) throw UpdateCheckFailure(UpdateCheckError.SignInRequired, "", null)
                appDistributionSource.signInTester()
            }
            appDistributionSource.latestRelease()
        }
    }

    /**
     * The three offer rules, in the order that makes the cheap ones first. All must hold.
     *
     * Rule 3's second branch covers `==` and `<` together: a version code *lower* than the
     * dismissed one is possible — a tag cut from an older commit than the latest tester build — and
     * "not newer, so wait the snooze out" is the right answer for both.
     */
    private suspend fun shouldOffer(info: UpdateInfo): Boolean {
        if (info.versionCode <= buildInfo.versionCode) return false
        if (!elapsed(time.now(), settingsRepository.lastUpdateDialogAt.first(), DIALOG_INTERVAL_MILLIS)) {
            return false
        }
        if (info.versionCode > settingsRepository.dismissedUpdate(info.source).first()) return true
        return elapsed(
            time.now(),
            settingsRepository.dismissedUpdateAt(info.source).first(),
            SNOOZE_MILLIS,
        )
    }

    /**
     * Records a "Not now" — both halves of the dismissal in one write — and clears the offer.
     *
     * A tap outside or Back takes this same path: otherwise the dialog would return on the next
     * launch, which is the complaint the snooze exists to bound.
     */
    suspend fun dismiss(info: UpdateInfo) {
        settingsRepository.setDismissedUpdate(info.source, info.versionCode, time.now())
        _available.value = null
    }

    /**
     * Stamps the 1-day dialog floor. Called when the dialog **actually composes**, not when the
     * check finds something — a user who taps "Check now" in Settings and never returns to the
     * Library tab was never interrupted, and must not burn the floor. Stamping it in [check] would
     * silently swallow the first offer of every new version.
     */
    suspend fun markDialogShown() {
        settingsRepository.setLastUpdateDialogAt(time.now())
    }

    /** Clears the offer without recording a dismissal — the user chose to update. */
    fun clearAvailable() {
        _available.value = null
    }

    /**
     * The in-app install, for [UpdateSource.APP_DISTRIBUTION] only — the GitHub channel links out
     * to a browser instead, which its caller does.
     *
     * Suspends until the download **and** install finish, so this is not fire-and-forget. Nothing
     * renders progress today (see the plan's backlog), but a failure still has to go somewhere the
     * user can read it, which is why it lands in [error] rather than being logged and lost.
     */
    suspend fun install() {
        try {
            appDistributionSource.install()
        } catch (e: UpdateCheckFailure) {
            _error.value = e.reason ?: UpdateCheckError.Unknown
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "In-app update install failed")
            _error.value = UpdateCheckError.InstallFailed
        }
    }

    /**
     * Switches the channel — and for App Distribution, only if a tester sign-in succeeds.
     *
     * The gate is here rather than in the settings ViewModel because it is update *policy*, next to
     * the rule about when sign-in may happen at all: this is a user gesture, so it is one of the
     * two paths allowed to open a Custom Tab.
     *
     * Persisting a channel that provably cannot answer would be a silent dead end — the user picks
     * it, nothing ever happens, and there is no way to tell "no updates" from "not entitled". So
     * the preference is written **after** the sign-in, never before, and the caller keeps rendering
     * the persisted channel until it lands: a cancelled sign-in visibly snaps back rather than
     * leaving a lie on screen.
     *
     * Switching *away* deliberately does not sign the tester out. Re-selecting should stay one tap,
     * and the signed-in state is Firebase-global rather than this feature's to clear.
     *
     * **Runs on the application scope, not the caller's** — this is not a style choice. The Custom
     * Tab is a separate task, and returning from it through `SignInResultActivity` recreates the
     * activity, which clears the settings screen's `ViewModelStore`. On `viewModelScope` the
     * coroutine was therefore cancelled between a *successful* sign-in and the write, so the
     * channel silently stayed Off: verified on-device 2026-08-06, twice, before this was moved.
     */
    fun selectSource(source: UpdateSource) = dispatchers.applicationScope.launch {
        _error.value = null
        if (source != UpdateSource.APP_DISTRIBUTION || appDistributionSource.isTesterSignedIn()) {
            settingsRepository.setUpdateSource(source)
            return@launch
        }
        _signingIn.value = true
        try {
            appDistributionSource.signInTester()
            settingsRepository.setUpdateSource(source)
        } catch (e: UpdateCheckFailure) {
            // Cancelling is the ordinary "no thanks" path, and reads as an explanation rather than
            // a fault — see update_error_sign_in_cancelled.
            _error.value = e.reason ?: UpdateCheckError.Unknown
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Tester sign-in failed")
            _error.value = UpdateCheckError.Unknown
        } finally {
            _signingIn.value = false
        }
    }
}
