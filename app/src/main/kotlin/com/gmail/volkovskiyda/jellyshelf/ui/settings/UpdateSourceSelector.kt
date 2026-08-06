package com.gmail.volkovskiyda.jellyshelf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateSource

/** The Material minimum for anything a finger has to hit — same constant [ThemeModeSwitch] holds. */
private val MIN_TOUCH_TARGET = 48.dp

private val PROGRESS_SIZE = 16.dp

/** The label each channel shows on its chip, and in the group's spoken state description. */
val UpdateSource.labelRes: Int
    get() = when (this) {
        UpdateSource.NONE -> R.string.update_source_off
        UpdateSource.GITHUB -> R.string.update_source_github
        UpdateSource.APP_DISTRIBUTION -> R.string.update_source_app_distribution
    }

/** One line saying what picking this channel actually does. */
val UpdateSource.hintRes: Int
    get() = when (this) {
        UpdateSource.NONE -> R.string.update_source_off_hint
        UpdateSource.GITHUB -> R.string.update_source_github_hint
        UpdateSource.APP_DISTRIBUTION -> R.string.update_source_app_distribution_hint
    }

/**
 * The sentence each failure reason renders as. Exhaustive with no `else`, so a new
 * [UpdateCheckError] case cannot ship without wording — which is the whole promise of the type:
 * a failed check says why, and never degrades into a silent "no update available".
 */
val UpdateCheckError.messageRes: Int
    get() = when (this) {
        UpdateCheckError.ApiDisabled -> R.string.update_error_api_disabled
        UpdateCheckError.NotATester -> R.string.update_error_not_a_tester
        UpdateCheckError.SignInCancelled -> R.string.update_error_sign_in_cancelled
        UpdateCheckError.SignInRequired -> R.string.update_error_sign_in_required
        UpdateCheckError.Network -> R.string.update_error_network
        UpdateCheckError.DownloadFailed -> R.string.update_error_download_failed
        UpdateCheckError.InstallFailed -> R.string.update_error_install_failed
        UpdateCheckError.InstallCancelled -> R.string.update_error_install_cancelled
        UpdateCheckError.Interrupted -> R.string.update_error_interrupted
        UpdateCheckError.NotSupported -> R.string.update_error_not_supported
        UpdateCheckError.Unknown -> R.string.update_error_unknown
    }

/**
 * The three-way update-channel pick.
 *
 * Deliberately **not** shaped like [ThemeModeSwitch], despite both offering three options: that
 * control is a single tap-to-advance target because the theme sequence is a ping-pong that needs a
 * direction. Channels are a plain pick with no ordering, so each one is its own target and a direct
 * jump is right — hence chips rather than a track.
 *
 * Stateless: it takes the selection and reports taps, and never touches Firebase. That is what lets
 * it be tested on the debug variant even though the section hosting it does not exist there.
 *
 * @param signingIn a tester sign-in is in flight. The Custom Tab is a separate task, so the user can
 *   come back to this screen mid-flow and must not be able to start a second one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSourceSelector(
    selected: UpdateSource,
    onSelect: (UpdateSource) -> Unit,
    modifier: Modifier = Modifier,
    signingIn: Boolean = false,
) {
    val stateDescription = stringResource(R.string.update_source_selected, stringResource(selected.labelRes))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // The group reports which channel is selected, so TalkBack announces the state once rather
        // than leaving the user to infer it from three separately-toggled chips.
        modifier = modifier.semantics { this.stateDescription = stateDescription },
    ) {
        UpdateSource.entries.forEach { source ->
            FilterChip(
                selected = source == selected,
                onClick = { onSelect(source) },
                enabled = !signingIn,
                label = { Text(stringResource(source.labelRes)) },
                trailingIcon = if (signingIn && source == UpdateSource.APP_DISTRIBUTION) {
                    { CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE)) }
                } else {
                    null
                },
                // A chip's own height is below the Material minimum, and this one is a primary
                // control rather than a filter on a dense list — ThemeModeSwitchTest exists
                // because that was got wrong once already.
                modifier = Modifier.sizeIn(minHeight = MIN_TOUCH_TARGET),
            )
        }
    }
}
