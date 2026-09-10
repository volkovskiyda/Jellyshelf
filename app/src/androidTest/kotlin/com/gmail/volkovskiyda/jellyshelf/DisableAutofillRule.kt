package com.gmail.volkovskiyda.jellyshelf

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Switches the device's autofill service off for the duration of a test, and restores it after.
 *
 * The live journey types the real `.test.env` password into the sign-in form, and the form is
 * deliberately autofill-aware — the fields carry content types so a real user's password manager
 * can fill them. On a device with Google Password Manager active, a successful sign-in therefore
 * commits the autofill session and raises the system's full-screen *"Save password to Google
 * Password Manager?"* sheet — over whatever the test does next, and offering to persist the test
 * account's real password on the device with a reveal toggle beside it.
 *
 * Disabled for the test rather than dismissed on sight, for three reasons. The sheet lives in a
 * window of its own, where Compose's clicks and Back cannot reach — the same reason
 * [JourneyPermissionsRule] grants instead of dismissing. Its copy and layout belong to Play
 * services and the device locale, so any selector against it is a moving target. And no service
 * means no session, so nothing is committed and nothing has the password to offer — which is the
 * only version of "removed" that never flashes on screen at all.
 *
 * The restore runs in a `finally`, so a red test still puts the user's autofill back. Only a test
 * that types real credentials needs this; the autofill behaviour tests must keep the service on.
 */
class DisableAutofillRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            // Prints the stored component, or the literal "null" when nothing is set.
            val previous = shell("settings get secure autofill_service").trim()
            shell("settings put secure autofill_service null")
            try {
                base.evaluate()
            } finally {
                if (previous.isEmpty() || previous == "null") {
                    shell("settings delete secure autofill_service")
                } else {
                    shell("settings put secure autofill_service $previous")
                }
            }
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
    }
}
