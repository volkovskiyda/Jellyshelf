package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.ui.settings.messageRes
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private const val PERCENT = 100

/**
 * How long a failed install states its reason before clearing itself.
 *
 * Long enough to read one sentence, short enough not to become litter the user has to clear. The
 * "Dismiss" action and a swipe both still cut it short; nothing here waits on the user.
 */
private val FAILURE_VISIBLE_FOR = 3.seconds

/**
 * Which *snackbar* an install state belongs to, as opposed to what it says.
 *
 * This is the whole fix for the flicker: `showSnackbar` suspends until its snackbar is gone, so
 * re-keying a `LaunchedEffect` on the message text tore the old one down and animated a new one in
 * on **every** progress tick. Progress therefore has one key for its whole life — the percentage
 * changes inside a snackbar that is never dismissed — and only a genuine change of subject
 * (running → failed, or either → nothing) starts a new one.
 *
 * `internal` so [InstallSnackbarKeyTest] can pin that a download's ticks all share a key, which no
 * assertion about rendered text can show.
 */
internal fun installSnackbarKey(state: InstallState?): String? = when (state) {
    null -> null
    is InstallState.Failed -> "failed"
    is InstallState.Running -> "running"
}

/**
 * What an in-app install is doing, as a sentence.
 *
 * A percentage only while [InstallStage.DOWNLOADING] with a known total: the SDK reports
 * `apkFileTotalBytes = 0` until the transfer actually starts, and "0%" for an unknown size is a
 * worse answer than not claiming a number at all.
 */
@Composable
fun installMessage(state: InstallState): String = when (state) {
    is InstallState.Failed -> stringResource(state.reason.messageRes)
    is InstallState.Running -> runningMessage(state)
}

@Composable
private fun runningMessage(state: InstallState.Running): String = when (state.stage) {
    InstallStage.PREPARING -> stringResource(R.string.update_install_preparing)
    InstallStage.INSTALLING -> stringResource(R.string.update_install_installing)
    InstallStage.DOWNLOADING -> {
        val percent = state.fraction?.let { (it * PERCENT).toInt() }
        if (percent == null) {
            stringResource(R.string.update_install_downloading_unknown)
        } else {
            stringResource(R.string.update_install_downloading, percent)
        }
    }
}

/**
 * Marks the one snackbar whose text is read from live state rather than from what it was shown
 * with. The host is the app's only one, so anything else posted through it — the selection undo,
 * say — has to keep the message it was given, not inherit an install's percentage.
 */
private class InstallSnackbarVisuals(override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/** As above, for the failure snackbar, which does carry an action. */
private class InstallFailureVisuals(
    override val message: String,
    override val actionLabel: String,
) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/**
 * The snackbar host to hand to a `Scaffold`, rendering [state] rather than the text an *install*
 * snackbar was shown with.
 *
 * Taking the label from live state instead of `data.visuals.message` is what lets the percentage
 * climb in place: the snackbar is shown once per subject (see [installSnackbarKey]) and only this
 * `Text` recomposes as bytes arrive. `visuals.message` stays the fallback for the moment before
 * [state] and the host agree, and is what the accessibility announcement on first show reads.
 *
 * Only for the install's own snackbars, which is what [InstallSnackbarVisuals] marks them as. This
 * is the app's single host — see `LocalSnackbarHostState` — and anything else posted through it
 * keeps the message it was given.
 */

@Composable
fun InstallSnackbarHost(hostState: SnackbarHostState, state: InstallState?) {
    SnackbarHost(hostState) { data ->
        // Keyed on the data so each snackbar swipes from a fresh, settled state rather than
        // inheriting the dismissed one its predecessor ended in.
        key(data) {
            val swipeState = rememberSwipeToDismissBoxState()
            // A swipe is a dismissal like any other: it resolves showSnackbar, which is what
            // clears a failure upstream. It is also the only way out of a progress snackbar that
            // never resolves — an install whose UpdateTask neither succeeds nor fails leaves an
            // indefinite snackbar with no action on it, which is exactly what happened on device.
            LaunchedEffect(swipeState.currentValue) {
                if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
            }
            SwipeToDismissBox(state = swipeState, backgroundContent = {}) {
                Snackbar(
                    action = data.visuals.actionLabel?.let { label ->
                        {
                            // A snackbar is drawn on the *inverse* surface — light in a dark app —
                            // and its action label has its own colour for that reason. A plain
                            // TextButton overrides it with the theme's `primary`, which is picked
                            // to sit on the normal surface: in dark mode that is a pale purple on
                            // a near-white snackbar, and the action all but disappears. Reading
                            // the colour back out of the composition keeps whatever `Snackbar`
                            // provides here, rather than pinning a second copy of the default.
                            val actionColor = LocalContentColor.current
                            TextButton(
                                onClick = data::performAction,
                                colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                            ) { Text(label) }
                        }
                    },
                ) {
                    val isInstall = data.visuals is InstallSnackbarVisuals ||
                        data.visuals is InstallFailureVisuals
                    Text(
                        if (isInstall) {
                            state?.let { installMessage(it) } ?: data.visuals.message
                        } else {
                            data.visuals.message
                        },
                    )
                }
            }
        }
    }
}

/**
 * Drives [hostState] from [state], so the install narrates itself wherever the user happens to be.
 *
 * A snackbar rather than something on one screen, because the install is app-wide: it is started
 * from a dialog that closes immediately, it outlives whatever screen was open, and the user is free
 * to navigate while the APK downloads.
 *
 * **Progress is [SnackbarDuration.Indefinite]** — it ends when the install does, not on a timer,
 * and state going null cancels this effect, which cancels `showSnackbar` and takes the snackbar
 * with it. **A failure is not**: it has been read once it has been read, so it clears itself after
 * [FAILURE_VISIBLE_FOR] rather than sitting there until the user deals with it. The timeout wraps
 * the call instead of using `SnackbarDuration.Short` so the interval is this file's decision and
 * not Material's 4 seconds.
 *
 * Either way the failure is cleared upstream exactly once — the timeout, the action and a swipe all
 * resolve the same `showSnackbar` call.
 */
@Composable
fun InstallProgressEffect(
    state: InstallState?,
    hostState: SnackbarHostState,
    onFailureDismissed: () -> Unit,
) {
    // Read outside the effect so the initial label is right; it is not a key, so later progress
    // does not restart the snackbar.
    val message = state?.let { installMessage(it) }
    val dismiss = stringResource(R.string.dismiss)
    LaunchedEffect(installSnackbarKey(state)) {
        if (message == null) return@LaunchedEffect
        if (state !is InstallState.Failed) {
            hostState.showSnackbar(InstallSnackbarVisuals(message))
            return@LaunchedEffect
        }
        withTimeoutOrNull(FAILURE_VISIBLE_FOR) {
            hostState.showSnackbar(InstallFailureVisuals(message, dismiss))
        }
        onFailureDismissed()
    }
}
