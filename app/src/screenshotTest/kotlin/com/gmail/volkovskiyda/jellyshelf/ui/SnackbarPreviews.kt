package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError

// Goldens for AppSnackbar — the one rendering behind the app's single host, so every snackbar
// (selection undo, install progress, install failure) looks like these. One golden per shape
// rather than per message: no action, a short message with an action, and a long message that
// wraps around its action. Each in both themes, because the snackbar sits on the *inverse*
// surface — dark-on-light in a dark app — and the dark goldens are what pin the action-colour
// fix that a plain TextButton would break.

private const val SNACKBAR_WIDTH = 400

private class PreviewSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/**
 * A snackbar's data with no host behind it. A real one only exists while a `showSnackbar` call is
 * suspended, which a single rendered preview frame cannot arrange — this is why `AppSnackbar` is
 * exposed separately from `InstallSnackbarHost` at all.
 */
private class PreviewSnackbarData(message: String, actionLabel: String? = null) : SnackbarData {
    override val visuals = PreviewSnackbarVisuals(message, actionLabel)
    override fun dismiss() = Unit
    override fun performAction() = Unit
}

/** Mid-download, with a percentage — the one snackbar in the app that carries no action at all. */
private val downloading = InstallState.Running(
    InstallStage.DOWNLOADING,
    bytesDownloaded = 420,
    totalBytes = 1000,
)

/** The longest failure the install can report; what a message looks like wrapped around Dismiss. */
private val longFailure = InstallState.Failed(UpdateCheckError.ApiDisabled)

@PreviewTest
@Preview(widthDp = SNACKBAR_WIDTH, showBackground = true)
@Composable
private fun SnackbarNoAction() {
    PreviewTheme {
        AppSnackbar(PreviewSnackbarData(installMessage(downloading)), state = null)
    }
}

@PreviewTest
@Preview(widthDp = SNACKBAR_WIDTH, showBackground = true)
@Composable
private fun SnackbarNoActionDark() {
    PreviewTheme(darkTheme = true) {
        AppSnackbar(PreviewSnackbarData(installMessage(downloading)), state = null)
    }
}

@PreviewTest
@Preview(widthDp = SNACKBAR_WIDTH, showBackground = true)
@Composable
private fun SnackbarWithAction() {
    PreviewTheme {
        AppSnackbar(
            PreviewSnackbarData(
                stringResource(R.string.selected_count, 27),
                stringResource(R.string.undo),
            ),
            state = null,
        )
    }
}

@PreviewTest
@Preview(widthDp = SNACKBAR_WIDTH, showBackground = true)
@Composable
private fun SnackbarWithActionDark() {
    PreviewTheme(darkTheme = true) {
        AppSnackbar(
            PreviewSnackbarData(
                stringResource(R.string.selected_count, 27),
                stringResource(R.string.undo),
            ),
            state = null,
        )
    }
}

@PreviewTest
@Preview(widthDp = SNACKBAR_WIDTH, showBackground = true)
@Composable
private fun SnackbarLongText() {
    PreviewTheme {
        AppSnackbar(
            PreviewSnackbarData(installMessage(longFailure), stringResource(R.string.dismiss)),
            state = null,
        )
    }
}

@PreviewTest
@Preview(widthDp = SNACKBAR_WIDTH, showBackground = true)
@Composable
private fun SnackbarLongTextDark() {
    PreviewTheme(darkTheme = true) {
        AppSnackbar(
            PreviewSnackbarData(installMessage(longFailure), stringResource(R.string.dismiss)),
            state = null,
        )
    }
}
