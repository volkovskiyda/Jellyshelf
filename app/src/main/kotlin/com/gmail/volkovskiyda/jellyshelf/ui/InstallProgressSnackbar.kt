package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.ui.settings.messageRes

private const val PERCENT = 100

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
 * The snackbar host to hand to a `Scaffold`, rendering [state] rather than the text the snackbar
 * was shown with.
 *
 * Taking the label from live state instead of `data.visuals.message` is what lets the percentage
 * climb in place: the snackbar is shown once per subject (see [installSnackbarKey]) and only this
 * `Text` recomposes as bytes arrive. `visuals.message` stays the fallback for the moment before
 * [state] and the host agree, and is what the accessibility announcement on first show reads.
 */
@Composable
fun InstallSnackbarHost(hostState: SnackbarHostState, state: InstallState?) {
    SnackbarHost(hostState) { data ->
        Snackbar(
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = data::performAction) { Text(label) }
                }
            },
        ) {
            Text(state?.let { installMessage(it) } ?: data.visuals.message)
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
 * [SnackbarDuration.Indefinite] throughout: progress ends when the install does, not on a timer,
 * and a failure is there to be read and dismissed. State going null cancels this effect, which
 * cancels `showSnackbar` and takes the snackbar with it — that is how a finished install clears.
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
        val failed = state is InstallState.Failed
        hostState.showSnackbar(
            message = message,
            actionLabel = if (failed) dismiss else null,
            duration = SnackbarDuration.Indefinite,
        )
        // Only a failure carries an action, so reaching here means the user dismissed one; a
        // running install ends by the state changing under us, which cancels this before it
        // returns.
        if (failed) onFailureDismissed()
    }
}
