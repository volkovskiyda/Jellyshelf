package com.gmail.volkovskiyda.jellyshelf.ui.library

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.NotificationPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.ui.NotificationPermissionDialog
import com.gmail.volkovskiyda.jellyshelf.ui.PermissionReadout
import com.gmail.volkovskiyda.jellyshelf.ui.permissionReadout
import org.koin.compose.koinInject

/**
 * Asks for `POST_NOTIFICATIONS` the first time the library has anything in it.
 *
 * Hosted by [LibraryScreen] rather than by the player, which used to fire the bare system dialog
 * over the controls of a just-started video. A non-empty library is the trigger because it is the
 * first moment the app has demonstrably worked for this user, and nothing is playing that a dialog
 * could interrupt; [NotificationPrompt] explains why the rationale dialog then has to supply the
 * context that moment no longer carries, and how long a decline lasts.
 *
 * ## Why it defers to the update offer
 *
 * `MainActivity` hosts the update dialog on this same screen, and two stacked dialogs are one
 * dialog nobody reads. The offer wins: it is rarer, it expires, and it is the one with a download
 * behind it. This prompt simply waits for the next visit with no offer pending — a week either way
 * costs it nothing.
 */
// InlinedApi: the one remaining mention of POST_NOTIFICATIONS, a compile-time constant that is
// only ever *used* behind the permissionExists gate below — on older platforms it is an inert
// string in the APK, not a call.
@SuppressLint("InlinedApi")
@Composable
internal fun NotificationPermissionPrompt(
    hasVideos: Boolean,
    prompt: NotificationPrompt = koinInject(),
    updateChecker: UpdateChecker = koinInject(),
    // The permission asked for, at every step that names one — the readout and the system request
    // "Allow" fires. One value threaded through rather than three spellings of the same constant,
    // so pointing this prompt at another permission is a parameter, not an edit. The dialog copy
    // and [NotificationPrompt]'s policy are notification-specific today; they are the seams to
    // generalize next if a second permission ever needs this flow.
    permission: String = Manifest.permission.POST_NOTIFICATIONS,
    // Below API 33 (minSdk is 30) notifications need no permission at all: nothing is shown and
    // nothing is requested. A parameter rather than a check made in here, so the composable itself
    // is not welded to one API level — the host (or a test) states whether the permission exists,
    // and the default states it the one true way. [NotificationPrompt.due] carries the same rule
    // as policy; this one keeps the permission machinery below it out of the composition.
    permissionExists: Boolean = koinInject<BuildInfo>().isAtLeast(Build.VERSION_CODES.TIRAMISU),
    readPermission: ((Activity) -> PermissionReadout)? = null,
) {
    if (!permissionExists) return

    // No activity, no permission request and no rationale to read — a composition outside one
    // (previews, host-side rendering) simply never prompts.
    val activity = LocalActivity.current ?: return

    // Null until the store answers. Deliberately not defaulted to "never asked": that would show
    // the dialog for one frame on every cold start before the real timestamp arrived to close it.
    val promptState by prompt.state.collectAsStateWithLifecycle(initialValue = null)
    val update by updateChecker.available.collectAsStateWithLifecycle()
    var visible by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whatever Android answered, the question has now been put to it — which is the fact next
        // week's decision needs. Recorded on the application scope, so the write survives the
        // activity coming back to the foreground behind the system dialog.
        prompt.record(systemAsked = true)
    }

    LaunchedEffect(hasVideos, promptState, update) {
        // Already on screen: re-deciding would only fight the buttons.
        if (visible) return@LaunchedEffect
        val state = promptState ?: return@LaunchedEffect
        if (!hasVideos || update != null) return@LaunchedEffect
        val readout = readPermission?.invoke(activity) ?: activity.permissionReadout(permission)
        visible = prompt.due(
            state = state,
            granted = readout.granted,
            shouldShowRationale = readout.shouldShowRationale,
        )
    }

    if (!visible) return
    NotificationPermissionDialog(
        onAllow = {
            // Ours closes as the system's opens, rather than lingering behind it. Nothing is
            // recorded here — the launcher's result does that, so an abandoned system dialog is
            // still counted as having reached Android.
            visible = false
            launcher.launch(permission)
        },
        onDismiss = {
            visible = false
            prompt.record(systemAsked = false)
        },
    )
}
