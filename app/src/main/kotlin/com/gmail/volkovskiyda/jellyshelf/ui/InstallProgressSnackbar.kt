package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.ui.settings.messageRes

private const val PERCENT = 100

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
 * Drives [hostState] from [state], so the install narrates itself wherever the user happens to be.
 *
 * A snackbar rather than something on one screen, because the install is app-wide: it is started
 * from a dialog that closes immediately, it outlives whatever screen was open, and the user is free
 * to navigate while the APK downloads.
 *
 * Re-shown on every message change — `showSnackbar` suspends until the current one is gone, so a
 * new [LaunchedEffect] key cancels the old call and replaces the text instead of queueing behind
 * it. Progress is [SnackbarDuration.Indefinite] because it ends when the install does, not on a
 * timer; a failure is dismissible, and dismissing it clears the state so it cannot come back on the
 * next recomposition.
 */
@Composable
fun InstallProgressEffect(
    state: InstallState?,
    hostState: SnackbarHostState,
    onFailureDismissed: () -> Unit,
) {
    val message = state?.let { installMessage(it) }
    val dismiss = stringResource(R.string.dismiss)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val failed = state is InstallState.Failed
        hostState.showSnackbar(
            message = message,
            actionLabel = if (failed) dismiss else null,
            withDismissAction = failed,
            duration = SnackbarDuration.Indefinite,
        )
        // Only a failure can be dismissed, so reaching here means the user did — anything else
        // ends by the state changing under us, which cancels this effect before it returns.
        if (failed) onFailureDismissed()
    }
}
