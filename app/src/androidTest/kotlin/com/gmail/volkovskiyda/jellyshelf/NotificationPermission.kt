package com.gmail.volkovskiyda.jellyshelf

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Grants `POST_NOTIFICATIONS` before the app can ask for it.
 *
 * Every test that launches the real `MainActivity` against a populated library needs this: the
 * library screen prompts for the permission the first time it has videos in it, and both that
 * dialog and Android's own sit in windows of their own — over the content the test is acting on,
 * where Compose's own click and Back handling cannot reach them.
 *
 * Granting is the honest fix rather than dismissing the prompt: these tests are about the library,
 * the demo journey and the update offer, and a device that has already granted the permission is
 * the ordinary state after the first launch.
 *
 * A no-op once granted, and below API 33 there is no such permission to grant.
 */
fun grantNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.executeShellCommand(
        "pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS",
    ).close()
}

/**
 * [grantNotificationPermission] as a rule, for the tests whose activity is launched by a rule of
 * their own — `createAndroidComposeRule<MainActivity>()` starts the activity as it is applied,
 * which is before any `@Before` runs. Declare this at `order = 0` and the compose rule after it.
 *
 * Not `GrantPermissionRule`: that one fails outright on a permission the platform has never heard
 * of, and this suite still runs at API 31 — two levels below the API 33 that introduced
 * `POST_NOTIFICATIONS`.
 */
class NotificationPermissionRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            grantNotificationPermission()
            base.evaluate()
        }
    }
}
