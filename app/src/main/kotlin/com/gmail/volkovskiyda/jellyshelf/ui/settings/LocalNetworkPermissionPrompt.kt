package com.gmail.volkovskiyda.jellyshelf.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.ui.LocalNetworkPermissionDialog
import org.koin.compose.koinInject

/**
 * Asks for `ACCESS_LOCAL_NETWORK`, with the reason first, while there is still a sign-in to save.
 *
 * From Android 16 a connection to an address on the user's own network — `192.168.*`, `10.*`,
 * `172.16-31.*`, link-local, `.local` — needs this on top of `INTERNET`. A self-hosted Jellyfin is
 * usually exactly that, so for this app the permission is not an edge case; it is the connection.
 *
 * ## Why this is load-bearing, not a rare fallback
 *
 * Declaring the permission in the manifest is not by itself a grant. `REVOKE_WHEN_REQUESTED` reads
 * as though the platform hands it over at install — this KDoc used to say exactly that — but
 * measured on the Android 17 tablet against the shipped APK, a clean install (full uninstall,
 * install, launch) sits at `granted=false` with the app op on `ignore`, both before and after first
 * launch. The release install beside it reads `allow` with a `rejectTime` earlier the same day:
 * blocked first, granted later by an explicit act. So on that device this dialog is *how* the
 * permission comes to be asked for, and an app that never asks waits out its 30-second timeout and
 * blames the server, with no way back.
 *
 * It is still silent whenever the permission is already there — the `granted` read below returns
 * early — so a user who has it never sees this.
 *
 * That is also why the trigger is deliberately not "the server URL looks local". Deciding that
 * needs a DNS lookup — this app's own server is a *hostname* that resolves to a private
 * address — and a composable cannot resolve one. Asking whenever the permission is missing and
 * there is no session yet costs one dialog, on the screen where the user was about to sign in
 * anyway, and is the only trigger that fires before the 30 seconds are spent rather than after.
 *
 * ## Why it is hosted here
 *
 * By [SettingsScreen] rather than by [SettingsContent], for the same reason
 * `NotificationPermissionPrompt` sits outside `LibraryContent`: the content stays stateless and its
 * screenshot goldens keep rendering a screen with nothing on top of it.
 */
// InlinedApi: ACCESS_LOCAL_NETWORK is a compile-time constant, only ever *used* behind the
// permissionExists gate below — on older platforms it is an inert string in the APK, not a call.
@SuppressLint("InlinedApi")
@Composable
internal fun LocalNetworkPermissionPrompt(
    signedIn: Boolean,
    permission: String = Manifest.permission.ACCESS_LOCAL_NETWORK,
    // Below API 36 there is no such permission and local addresses need no extra grant. A parameter
    // rather than a check made in here, so the composable is not welded to one API level — the same
    // seam NotificationPermissionPrompt uses, and for the same reasons.
    permissionExists: Boolean = koinInject<BuildInfo>().isAtLeast(LOCAL_NETWORK_PERMISSION_API),
    // The one platform answer this needs, behind a seam because it cannot be faked on a device:
    // `pm revoke` kills the app's process, instrumentation included, so without this every gate
    // below is unreachable on a device that has ever run with the permission granted.
    readPermission: ((Activity) -> Boolean)? = null,
) {
    if (!permissionExists || signedIn) return

    // No activity, no permission request — a composition outside one (previews, host-side
    // rendering) simply never prompts.
    val activity = LocalActivity.current ?: return

    // Read once per composition rather than in an effect: unlike the notification prompt there is
    // no stored policy to wait for, so there is no frame where the answer is unknown and a default
    // would flash the dialog.
    val granted = remember(activity, permission) {
        readPermission?.invoke(activity) ?: activity.hasPermission(permission)
    }

    // Survives rotation and the activity coming back from behind the system dialog. Not persisted:
    // a decline holds for this visit to the screen, and the next launch of an app that still cannot
    // reach its server is a launch where the question is worth putting again.
    var declined by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whatever Android answered, the question has been put — and re-showing our rationale over
        // its result would be the nagging this is meant to avoid.
        asked = true
    }

    if (granted || declined || asked) return
    LocalNetworkPermissionDialog(
        onAllow = {
            // Ours closes as the system's opens, rather than lingering behind it.
            asked = true
            launcher.launch(permission)
        },
        onDismiss = { declined = true },
    )
}

/**
 * API 36 (Android 16), where local network protection and the permission gating it arrive.
 *
 * A literal rather than `Build.VERSION_CODES.BAKLAVA`: [BuildInfo.isAtLeast] takes a plain Int by
 * design, and naming it here keeps the one place that has to know the level next to the reason.
 */
internal const val LOCAL_NETWORK_PERMISSION_API = Build.VERSION_CODES.BAKLAVA

private fun Activity.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
