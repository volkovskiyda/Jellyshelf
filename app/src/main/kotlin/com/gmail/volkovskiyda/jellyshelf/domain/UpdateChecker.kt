package com.gmail.volkovskiyda.jellyshelf.domain

import com.gmail.volkovskiyda.jellyshelf.data.remote.AppDistributionSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.GitHubReleaseSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateCheckFailure
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateOffer
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
 * - **Validity** — debug build, no real version code, no channel selected, and whether what the
 *   channel returned is actually newer than what is installed. *Both* paths stop here: "update to
 *   the build you are already running" is not an offer a user asked for by tapping "Check now",
 *   and the GitHub channel hands back its latest release whether or not it is an upgrade. A debug
 *   install is `…jellyshelf.debug` with `versionCode = 1` and a `-debug` version name, so every
 *   published release would read as newer; a locally assembled release without `-PbuildNumber`
 *   reports `1` and does the same.
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
// TooManyFunctions: this is one policy object, and its surface is the vocabulary the UI speaks —
// two ways to start a check, three ways an offer can end (dismiss, take it, be shown), the channel
// switch, and the install with its own outcome. Splitting it would put rules that read each other's
// state in two files. Suppressed at the declaration rather than baselined, so the finding stays
// visible if the class grows for a worse reason.
// LongParameterList: eight collaborators, every one of them injected and named at the use site.
// Bundling them into a holder would hide which rules depend on what, for one fewer line here.
@Suppress("TooManyFunctions", "LongParameterList")
class UpdateChecker(
    private val settingsRepository: SettingsRepository,
    private val gitHubSource: GitHubReleaseSource,
    private val appDistributionSource: AppDistributionSource,
    private val buildInfo: BuildInfo,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val updateCheckSchedule: UpdateCheckSchedule,
    private val apkInstaller: ApkInstall,
    private val installOutcome: InstallOutcome,
) {
    // MutableStateFlow, never @Volatile or an atomic: the project has carried zero @Volatile since
    // 2026-07-29 and this is not the place to reintroduce one.
    private val _available = MutableStateFlow<UpdateOffer?>(null)

    /** The update to offer, or null. Null until a check finds something — most launches. */
    val available: StateFlow<UpdateOffer?> = _available.asStateFlow()

    private val _error = MutableStateFlow<UpdateCheckError?>(null)

    /** Why the last check failed, for Settings to render. Cleared when a check starts. */
    val error: StateFlow<UpdateCheckError?> = _error.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _upToDate = MutableStateFlow(false)

    /**
     * The last **manual** check finished and found nothing newer to offer.
     *
     * Only manual checks set this. A cold-start check that finds nothing is the ordinary case and
     * has no news to report; announcing it would put a line on the settings screen that the user
     * never asked for and cannot explain. Cleared the moment the next check starts, so it always
     * describes the most recent answer rather than an old one.
     *
     * Mutually exclusive with [error] by construction — a check that threw never gets this far.
     */
    val upToDate: StateFlow<Boolean> = _upToDate.asStateFlow()

    private val _installState = MutableStateFlow<InstallState?>(null)

    /**
     * The in-app install: its progress while it runs, its reason if it fails, null otherwise.
     *
     * Hosted app-wide rather than on a screen, because the install outlives the dialog that starts
     * it and the user is free to navigate away while the APK downloads.
     */
    val installState: StateFlow<InstallState?> = _installState.asStateFlow()

    private val _signingIn = MutableStateFlow(false)

    /** A tester sign-in is in flight, from [selectSource]. The Custom Tab is a separate task. */
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    /** Whether the whole feature should be hidden from the UI — see [checkOnStart]'s first gate. */
    val isDebugBuild: Boolean = buildInfo.isDebug

    /**
     * Brings the daily background check into line with the current channel — scheduled while there
     * is a channel to check, cancelled otherwise.
     *
     * Debug builds never schedule: the whole update feature is hidden there, and a worker asking
     * GitHub about a version that does not exist would be the one part of it still running.
     */
    private fun applyBackgroundSchedule(source: UpdateSource) {
        if (buildInfo.isDebug || source == UpdateSource.NONE) {
            updateCheckSchedule.cancel()
        } else {
            updateCheckSchedule.schedule()
        }
    }

    /** Called once per process, after the graph is up — see JellyshelfApplication. */
    fun scheduleBackgroundCheck() {
        dispatchers.applicationScope.launch {
            applyBackgroundSchedule(settingsRepository.updateSource.first())
        }
    }

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
        dispatchers.applicationScope.launch { checkPeriodic() }
    }

    /**
     * The same automatic check as [checkOnStart], awaited rather than fired and forgotten — for the
     * background worker, which has nothing to look at afterwards unless it waits for the answer.
     *
     * Automatic, not manual: it must honour the once-a-day floor and the snooze exactly as a launch
     * does, and it must never sign a tester in, since there is no user present to meet the browser
     * that would open.
     */
    suspend fun checkPeriodic() = check(manual = false)

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
        _upToDate.value = false
        _checking.value = true
        try {
            val info = fetch(source, manual)
            // Stamped even when nothing was found, and *not* stamped on failure: the interval is
            // about how often we ask, so a transient outage must not buy a day of silence.
            settingsRepository.setLastUpdateCheckAt(time.now())
            // Newer-than-installed is a validity rule and binds both paths; only the politeness
            // windows in shouldOffer are the user's to skip by asking.
            val offer = info
                ?.takeIf { it.versionCode > buildInfo.versionCode && (manual || shouldOffer(it)) }
                ?.let { UpdateOffer(it, requested = manual) }
            _available.value = offer
            if (manual) _upToDate.value = offer == null
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
     * The two *politeness* rules, cheap one first — both must hold. The caller has already
     * established that [info] is newer than the installed build, which is not politeness and so is
     * not skippable.
     *
     * Rule 2's second branch covers `==` and `<` together: a version code *lower* than the
     * dismissed one is possible — a tag cut from an older commit than the latest tester build — and
     * "not newer, so wait the snooze out" is the right answer for both.
     */
    private suspend fun shouldOffer(info: UpdateInfo): Boolean {
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
     *
     * A no-op for an offer the user asked for ([UpdateOffer.requested]), for the same reason: the
     * floor bounds how often the app interrupts, and a dialog opened on request interrupts nobody.
     * Spending it there would mute the next *automatic* offer for a day — quite possibly of a build
     * newer than the one just shown.
     */
    suspend fun markDialogShown(offer: UpdateOffer) {
        if (offer.requested) return
        settingsRepository.setLastUpdateDialogAt(time.now())
    }

    /**
     * Promotes the offer already in hand to one the user asked for — the notification-tap path.
     *
     * Tapping the notification *is* asking, and it says nothing about which tab the app happens to
     * open on, so the offer has to be showable wherever that turns out to be. Flipping
     * [UpdateOffer.requested] is exactly that: the hosting gate already lets a requested offer
     * appear anywhere, and [markDialogShown] already declines to spend the once-a-day interrupt
     * floor on one — which is right here too, since a dialog the user summoned interrupts nobody.
     *
     * A no-op when there is no offer: the check that found it runs in a process the tap may have
     * outlived, and the cold-start check will find it again.
     */
    fun showRequested() {
        _available.value = _available.value?.copy(requested = true)
    }

    /** Clears the offer without recording a dismissal — the user chose to update. */
    fun clearAvailable() {
        _available.value = null
    }

    /**
     * Whether [install] can handle this offer, or the caller should open the browser instead.
     *
     * The only case it cannot is a GitHub release with no published digest — an older release, or
     * one GitHub has no `digest` for. There is nothing to verify such a download against, so it is
     * not installed in-app at all; the browser path it falls back to is exactly what every GitHub
     * update did before.
     */
    fun canInstall(info: UpdateInfo): Boolean =
        info.source == UpdateSource.APP_DISTRIBUTION || info.sha256 != null

    /**
     * The in-app install, for either channel.
     *
     * App Distribution's SDK downloads and verifies its own bytes. GitHub's are downloaded here and
     * checked against the digest the release published — see
     * [ApkInstaller][com.gmail.volkovskiyda.jellyshelf.data.install.ApkInstaller]. Both narrate
     * through [installState], so the two channels are indistinguishable on screen, which is the
     * point: the user picked a channel, not an install mechanism.
     *
     * Suspends until the install has been handed over, so this is not fire-and-forget, and reports
     * both progress and failure through [installState].
     *
     * A success clears the state rather than announcing itself: succeeding means the system
     * installer has taken over and the app is about to be replaced, so there is nobody left to
     * read a confirmation.
     */
    suspend fun install(info: UpdateInfo) {
        _installState.value = InstallState.Running(InstallStage.PREPARING)
        try {
            if (info.source == UpdateSource.APP_DISTRIBUTION) {
                appDistributionSource.install { _installState.value = it }
                _installState.value = null
            } else {
                // The system installer reports asynchronously, through a broadcast that outlives
                // this call — so the outcome is what clears or fails the state, not returning.
                installOutcome.observe { reason ->
                    _installState.value = reason?.let { InstallState.Failed(it) }
                    installOutcome.clear()
                }
                apkInstaller.downloadAndInstall(info) { _installState.value = it }
            }
        } catch (e: ApkInstallFailure) {
            installOutcome.clear()
            _installState.value = InstallState.Failed(e.reason)
        } catch (e: UpdateCheckFailure) {
            installOutcome.clear()
            _installState.value = InstallState.Failed(e.reason ?: UpdateCheckError.Unknown)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            installOutcome.clear()
            Timber.w(e, "In-app update install failed")
            _installState.value = InstallState.Failed(UpdateCheckError.InstallFailed)
        }
    }

    /** Drops a finished install's outcome once the user has seen it. */
    fun clearInstallState() {
        _installState.value = null
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
        // The old channel's answer says nothing about the new one's.
        _upToDate.value = false
        // Turning the feature off has to stop the background check too, or the daily wake-up
        // outlives the choice that justified it. Scheduling for a channel the sign-in below may
        // yet fail is deliberate: the worker's own first gate is the persisted source, so a check
        // that runs before the source is written simply returns.
        applyBackgroundSchedule(source)
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
