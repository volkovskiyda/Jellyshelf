package com.gmail.volkovskiyda.jellyshelf

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.gmail.volkovskiyda.jellyshelf.domain.LOCAL_NETWORK_PERMISSION_API
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Grants the runtime permissions the real `MainActivity` asks for by itself, before it can ask.
 *
 * Every test that launches the real activity needs this. Each prompt is a rationale dialog followed
 * by Android's own, and both sit in windows of their own over the content the test is acting on,
 * where Compose's click and Back handling cannot reach them. Granting is the honest fix rather than
 * dismissing: these tests are about the library, the demo journey, the update offer and the live
 * server, and a device that has already granted both is the ordinary state after a first launch.
 *
 * - `POST_NOTIFICATIONS`, from API 33: the library screen asks the first time it has videos in it.
 * - `ACCESS_LOCAL_NETWORK`, from API 37: the settings screen asks whenever the permission is
 *   missing, and a signed-out activity *starts* there — so on a clean install the dialog is the
 *   first thing on screen. Its absence costs more than a dialog: a connection to a server on the
 *   device's own subnet is dropped rather than refused, and the instrumented process shares the
 *   app's uid, so the live layer's reachability probe is dropped with it and every live test skips.
 *   Measured on the API 37 tablet AVD on 2026-09-10 against a server at the host's `10.0.2.2`:
 *   the probe times out, and `pm grant` alone flips the app op from `ignore` to `allow`.
 *
 * Gated on [LOCAL_NETWORK_PERMISSION_API] rather than tried on every level: on API 36 the
 * permission exists but the op already defaults to `allow`, so there is nothing to grant, and
 * below that `pm grant` fails on a permission the platform has never heard of.
 *
 * Both grants are idempotent, and no-ops once granted.
 */
fun grantJourneyPermissions() {
    grantNotificationPermission()
    grantLocalNetworkPermission()
}

/**
 * The notification half of [grantJourneyPermissions] on its own, for the one test that needs
 * exactly this permission held so the rationale's "Allow" hands over to a system dialog that
 * returns at once. Below API 33 there is no such permission to grant.
 */
fun grantNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    grant(Manifest.permission.POST_NOTIFICATIONS)
}

// InlinedApi: the constant is a string in a shell command, only ever sent behind the level gate.
@SuppressLint("InlinedApi")
private fun grantLocalNetworkPermission() {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API) return
    grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
}

/**
 * Drained to the end rather than merely closed, so the grant has landed before the caller goes
 * on to launch an activity that checks it — the same reason `DisableAutofillRule` reads its
 * commands out.
 */
private fun grant(permission: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val command = "pm grant ${instrumentation.targetContext.packageName} $permission"
    ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes() }
}

/**
 * [grantJourneyPermissions] as a rule, for the tests whose activity is launched by a rule of their
 * own — `createAndroidComposeRule<MainActivity>()` starts the activity as it is applied, which is
 * before any `@Before` runs. Declare this at `order = 0` and the compose rule after it.
 *
 * Not `GrantPermissionRule`: that one fails outright on a permission the platform has never heard
 * of, and this suite still runs at API 31 — two levels below the API 33 that introduced
 * `POST_NOTIFICATIONS`, and six below the local-network one.
 */
class JourneyPermissionsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            grantJourneyPermissions()
            base.evaluate()
        }
    }
}
