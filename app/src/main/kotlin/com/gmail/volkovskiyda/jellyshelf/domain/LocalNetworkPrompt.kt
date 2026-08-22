package com.gmail.volkovskiyda.jellyshelf.domain

import android.os.Build
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** How long an unanswered-or-declined prompt stays quiet. A "Not now" is a snooze; it expires. */
private val REASK_MILLIS = TimeUnit.DAYS.toMillis(7)

/**
 * API 37 (Android 17), where local network protection is *enforced* — not API 36, where the
 * permission first exists.
 *
 * The difference is measured, not assumed. On an API 36 emulator the app op defaults to `allow`,
 * LAN traffic flows, and a request for the permission is answered by the platform with "No
 * requestable permission in the request": `checkSelfPermission` says denied, but nothing is
 * blocked and nothing can be granted. On the API 37 tablet the same op reads `ignore`, connections
 * to the LAN are dropped, and a request reaches a real dialog the user can answer. Asking on 36
 * would therefore mean a weekly dialog whose "Allow" changes nothing — the exact failure mode
 * [LocalNetworkPrompt.due] refuses to produce for a permission Android has locked.
 *
 * An Android 16 user who turns local network protection on by hand (a developer scenario, since it
 * is off by default there) is not asked and grants it from system settings instead.
 *
 * Named here rather than at the call sites because [BuildInfo.isAtLeast] takes a plain Int, and
 * this keeps the one place that has to know the level next to the reason.
 */
const val LOCAL_NETWORK_PERMISSION_API = Build.VERSION_CODES.CINNAMON_BUN

/** What the store remembers about the prompt: when it was last answered, and how far it got. */
data class LocalNetworkPromptState(val answeredAt: Long, val systemAsked: Boolean)

/**
 * Decides whether to ask for `ACCESS_LOCAL_NETWORK`, and remembers the answer.
 *
 * ## Why the app asks at all
 *
 * With local network protection enforced, a connection to an address on the user's own network —
 * `192.168.*`, `10.*`, `172.16-31.*`, link-local, `.local` — needs this on top of `INTERNET`. A
 * self-hosted Jellyfin is usually exactly that, so for this app the permission is not an edge case;
 * it is the connection. Declaring it in the manifest is not a grant: measured on an Android 17
 * tablet, a clean install sits at `granted=false` with the app op on `ignore` both before and after
 * first launch. Android does not refuse the blocked connection either, it drops it — so the app
 * waits out its own 30-second timeout and reports the server as unreachable, which sends the user
 * to their router rather than to a permission screen.
 *
 * ## Who it asks
 *
 * Anyone missing the permission, signed in or not. It used to ask only while signed *out*, on the
 * theory that a session proves the connection already works — but the permission can go missing
 * long after a session exists (an install that signed in before the upgrade, a revoke), and with
 * this the app's only requester, those users had no way to be asked at all:
 * their app simply timed out and blamed the server. There is nothing to ask a user who holds the
 * permission, and [due] returns false for them, so the cost of asking more widely is one dialog to
 * someone whose server is not local — bounded by the week below, and closed for good once they
 * decline twice.
 *
 * ## Why the trigger is not "the server URL looks local"
 *
 * Deciding that needs a DNS lookup — a self-hosted server is usually a *hostname* that resolves to
 * a private address — and neither a composable nor this class can resolve one. Asking whenever the
 * permission is missing costs one dialog, on the screen the connection is configured from, and is
 * the only trigger that fires *before* the 30 seconds are spent rather than after. [rearm] is the
 * other half of that: a connection that has already failed is evidence, and it puts the question
 * back immediately instead of at the end of the week.
 *
 * A Koin `single` rather than a ViewModel, like [NotificationPrompt]: it is app-wide policy about
 * when the user may be interrupted, and tab ViewModels are cleared on every switch.
 */
class LocalNetworkPrompt(
    private val settingsRepository: SettingsRepository,
    private val time: TimeProvider,
    private val buildInfo: BuildInfo,
    private val dispatchers: DispatcherProvider,
) {
    /** Both persisted halves as one value, so a caller can't read a timestamp without its flag. */
    val state: Flow<LocalNetworkPromptState> = combine(
        settingsRepository.localNetworkPromptAt,
        settingsRepository.localNetworkSystemAsked,
    ) { answeredAt, systemAsked -> LocalNetworkPromptState(answeredAt, systemAsked) }

    /**
     * Whether the rationale dialog may be shown now.
     *
     * The two Android-only facts are parameters rather than reads, so this stays a plain unit-
     * testable decision: [granted] is `checkSelfPermission`, and [shouldShowRationale] is
     * `shouldShowRequestPermissionRationale` — which answers `false` both before the first ask and
     * after the second denial has locked the permission for good. [LocalNetworkPromptState.systemAsked]
     * is what separates those, and the pair of them is the only thing standing between a locked
     * permission and a weekly dialog whose "Allow" button provably does nothing.
     *
     * Below API 37 nothing local is blocked — see [LOCAL_NETWORK_PERMISSION_API] — so there is
     * nothing to ask for.
     */
    fun due(state: LocalNetworkPromptState, granted: Boolean, shouldShowRationale: Boolean): Boolean {
        if (!buildInfo.isAtLeast(LOCAL_NETWORK_PERMISSION_API)) return false
        if (granted) return false
        if (state.systemAsked && !shouldShowRationale) return false
        return elapsed(time.now(), state.answeredAt, REASK_MILLIS)
    }

    /**
     * Records an answer and starts the snooze. [systemAsked] is true when Android's own dialog was
     * reached, whatever it returned — a grant closes the question anyway, and a denial is the case
     * [due] has to reason about next week.
     *
     * **Runs on the application scope, not the caller's**, like [NotificationPrompt.record]: the
     * result of a permission request arrives while the activity is coming back to the foreground,
     * and a write cancelled on the way would leave the prompt due again on the very next frame.
     */
    fun record(systemAsked: Boolean) = dispatchers.applicationScope.launch {
        settingsRepository.setLocalNetworkPrompt(time.now(), systemAsked)
    }

    /**
     * Ends the snooze early, because a connection just failed in the way a missing grant fails.
     *
     * The stored `0` is the same "never answered" the store starts at, so this cancels a decline
     * rather than inventing a new state — and it deliberately does not touch the system-asked
     * flag, so a permission Android has already locked stays quiet through [due] no matter how
     * often the connection fails afterwards.
     *
     * On the application scope for the same reason as [record]: the caller is a ViewModel that a
     * tab switch can clear while the write is in flight.
     */
    fun rearm() = dispatchers.applicationScope.launch {
        settingsRepository.setLocalNetworkPrompt(timestamp = 0L, systemAsked = false)
    }
}
