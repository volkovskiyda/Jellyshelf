package com.gmail.volkovskiyda.jellyshelf.ui.library

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.NotificationPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.UpdateChecker
import com.gmail.volkovskiyda.jellyshelf.ui.NotificationPermissionDialog
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
@Composable
internal fun NotificationPermissionPrompt(
    hasVideos: Boolean,
    prompt: NotificationPrompt = koinInject(),
    updateChecker: UpdateChecker = koinInject(),
    buildInfo: BuildInfo = koinInject(),
) {
    // Below API 33 (minSdk is 30) notifications need no permission at all: nothing is shown and
    // nothing is requested. `POST_NOTIFICATIONS` is also a constant that does not exist on those
    // platforms, and this is the guard that says so — `isAtLeast` is annotated
    // `@ChecksSdkIntAtLeast`, so lint reads it as the version check it is. [NotificationPrompt.due]
    // carries the same rule as policy; this one keeps the API surface below it unreachable.
    if (!buildInfo.isAtLeast(Build.VERSION_CODES.TIRAMISU)) return

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
        visible = prompt.due(
            state = state,
            granted = activity.hasNotificationPermission(),
            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    if (!visible) return
    NotificationPermissionDialog(
        onAllow = {
            // Ours closes as the system's opens, rather than lingering behind it. Nothing is
            // recorded here — the launcher's result does that, so an abandoned system dialog is
            // still counted as having reached Android.
            visible = false
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onDismiss = {
            visible = false
            prompt.record(systemAsked = false)
        },
    )
}

/**
 * Whether the permission is already held. Meaningless below API 33 — where the constant is just a
 * string the platform has never heard of — which the annotation states and the caller's guard
 * enforces.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Activity.hasNotificationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
