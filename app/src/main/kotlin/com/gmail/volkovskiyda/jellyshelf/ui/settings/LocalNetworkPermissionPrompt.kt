package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import com.gmail.volkovskiyda.jellyshelf.domain.LOCAL_NETWORK_PERMISSION_API
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.ui.LocalNetworkPermissionDialog
import com.gmail.volkovskiyda.jellyshelf.ui.PermissionReadout
import com.gmail.volkovskiyda.jellyshelf.ui.permissionReadout
import org.koin.compose.koinInject

/**
 * Asks for `ACCESS_LOCAL_NETWORK`, with the reason first, from the screen the connection is
 * configured on. [LocalNetworkPrompt] holds the policy — who is asked, and how long an answer
 * lasts; this is the glue that renders it and writes the answers back.
 *
 * ## Why the decision waits for the store
 *
 * The prompt used to take the screen's `signedIn` flag and open whenever it was false. That flag
 * starts at the ViewModel's default `false` and only becomes true when the persisted snapshot
 * lands a frame or more later — and `NavDisplay` composes this entry while the *library* is still
 * painted. On a signed-in app the dialog therefore opened over the library and closed itself
 * before it could be answered, which is a permission that cannot be granted from inside the app at
 * all. Nothing here reads a screen state now: the only asynchronous input is the prompt's own
 * store, and it is null until the store answers, so there is no frame where the answer is unknown
 * and a default would flash the dialog.
 *
 * ## Why it is hosted by [SettingsScreen]
 *
 * Rather than by [SettingsContent], for the same reason `NotificationPermissionPrompt` sits
 * outside `LibraryContent`: the content stays stateless and its screenshot goldens keep rendering
 * a screen with nothing on top of it.
 */
// InlinedApi: ACCESS_LOCAL_NETWORK is a compile-time constant, only ever *used* behind the
// permissionExists gate below — on older platforms it is an inert string in the APK, not a call.
@SuppressLint("InlinedApi")
@Composable
internal fun LocalNetworkPermissionPrompt(
    prompt: LocalNetworkPrompt = koinInject(),
    permission: String = Manifest.permission.ACCESS_LOCAL_NETWORK,
    // Below API 37 nothing local is blocked and there is nothing to ask for — see
    // [LOCAL_NETWORK_PERMISSION_API], which is where the 36-versus-37 evidence lives. A parameter
    // rather than a check made in here, so the composable is not welded to one API level — the same
    // seam NotificationPermissionPrompt uses, and for the same reasons. [LocalNetworkPrompt.due]
    // carries the same rule as policy; this one keeps the permission machinery out of the
    // composition on a platform that has nothing to ask for.
    permissionExists: Boolean = koinInject<BuildInfo>().isAtLeast(LOCAL_NETWORK_PERMISSION_API),
    readPermission: ((Activity) -> PermissionReadout)? = null,
) {
    if (!permissionExists) return

    // No activity, no permission request and no rationale to read — a composition outside one
    // (previews, host-side rendering) simply never prompts.
    val activity = LocalActivity.current ?: return

    // Null until the store answers, deliberately: any default here is a guess that shows or hides
    // the dialog for a frame before the truth arrives — the flash this prompt was fixed for.
    val promptState by prompt.state.collectAsStateWithLifecycle(initialValue = null)
    var visible by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whatever Android answered, the question has now been put to it — which is the fact next
        // week's decision needs. Recorded on the application scope, so the write survives the
        // activity coming back to the foreground behind the system dialog.
        prompt.record(systemAsked = true)
    }

    LaunchedEffect(promptState) {
        // Already on screen: re-deciding would only fight the buttons.
        if (visible) return@LaunchedEffect
        val state = promptState ?: return@LaunchedEffect
        val readout = readPermission?.invoke(activity) ?: activity.permissionReadout(permission)
        visible = prompt.due(
            state = state,
            granted = readout.granted,
            shouldShowRationale = readout.shouldShowRationale,
        )
    }

    if (!visible) return
    LocalNetworkPermissionDialog(
        onAllow = {
            // Ours closes as the system's opens, rather than lingering behind it. Nothing is
            // recorded here — the launcher's result does that, so an abandoned system dialog is
            // still counted as having reached Android.
            visible = false
            launcher.launch(permission)
        },
        // Every way out except "Allow" is a decline, including a tap outside and Back, matching the
        // notification rationale. Declining never reaches the system dialog, so it cannot spend one
        // of the two denials that lock a permission for good — it starts the week instead.
        onDismiss = {
            visible = false
            prompt.record(systemAsked = false)
        },
    )
}
