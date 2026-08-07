package com.gmail.volkovskiyda.jellyshelf.domain

import android.os.Build
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** How long an unanswered-or-declined prompt stays quiet. A "Not now" is a snooze; it expires. */
private val REASK_MILLIS = TimeUnit.DAYS.toMillis(7)

/** What the store remembers about the prompt: when it was last answered, and how far it got. */
data class NotificationPromptState(val answeredAt: Long, val systemAsked: Boolean)

/**
 * Decides whether to ask for `POST_NOTIFICATIONS`, and remembers the answer.
 *
 * ## Why the app asks at all
 *
 * One consumer: the media notification `PlaybackService` posts while a video plays. Denied,
 * playback still works — there is just no way to control it, and no way back into it, once the
 * app is in the background.
 *
 * ## Why it asks on the library screen
 *
 * The ask used to fire on the player screen, in context but at the worst possible moment: the
 * system dialog opened over the controls of a video the user had just started. It now fires the
 * first time the library has something in it, which is the earliest point at which the app is
 * demonstrably working for this user and nothing is playing that a dialog could interrupt.
 *
 * That trades context for calm, so the context has to be supplied instead — hence the rationale
 * dialog in front of the system one. It is not decoration: on API 33+ the *second* denial locks
 * the permission for good, with no dialog ever shown again, so an out-of-context ask that earns a
 * reflex "Don't allow" is expensive. Our own dialog absorbs the reflex; "Not now" costs nothing
 * and comes back in [REASK_MILLIS].
 *
 * A Koin `single` rather than a ViewModel for the same reason as [UpdateChecker]: it is app-wide
 * policy about when the user may be interrupted, and tab ViewModels are cleared on every switch.
 */
class NotificationPrompt(
    private val settingsRepository: SettingsRepository,
    private val time: TimeProvider,
    private val buildInfo: BuildInfo,
    private val dispatchers: DispatcherProvider,
) {
    /** Both persisted halves as one value, so a caller can't read a timestamp without its flag. */
    val state: Flow<NotificationPromptState> = combine(
        settingsRepository.notificationPromptAt,
        settingsRepository.notificationSystemAsked,
    ) { answeredAt, systemAsked -> NotificationPromptState(answeredAt, systemAsked) }

    /**
     * Whether the rationale dialog may be shown now.
     *
     * The two Android-only facts are parameters rather than reads, so this stays a plain unit-
     * testable decision: [granted] is `checkSelfPermission`, and [shouldShowRationale] is
     * `shouldShowRequestPermissionRationale` — which answers `false` both before the first ask and
     * after the permission has been locked by a second denial. [NotificationPromptState.systemAsked]
     * is what separates those, and the pair of them is the only thing standing between a locked
     * permission and a weekly dialog whose "Allow" button provably does nothing.
     *
     * Below API 33 there is no such permission to hold, so there is nothing to ask for.
     */
    fun due(state: NotificationPromptState, granted: Boolean, shouldShowRationale: Boolean): Boolean {
        if (!buildInfo.isAtLeast(Build.VERSION_CODES.TIRAMISU)) return false
        if (granted) return false
        if (state.systemAsked && !shouldShowRationale) return false
        return elapsed(time.now(), state.answeredAt, REASK_MILLIS)
    }

    /**
     * Records an answer and starts the snooze. [systemAsked] is true when Android's own dialog was
     * reached, whatever it returned — a grant closes the question anyway, and a denial is the case
     * [due] has to reason about next week.
     *
     * **Runs on the application scope, not the caller's**, like [UpdateChecker.selectSource]: the
     * result of a permission request arrives while the activity is coming back to the foreground,
     * and a write cancelled on the way would leave the prompt due again on the very next frame.
     */
    fun record(systemAsked: Boolean) = dispatchers.applicationScope.launch {
        settingsRepository.setNotificationPrompt(time.now(), systemAsked)
    }
}
