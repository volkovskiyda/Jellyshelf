package com.gmail.volkovskiyda.jellyshelf.ui

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The two platform answers a runtime-permission prompt needs, read together so a caller cannot
 * take one without the other — their *combination* is what tells "never asked" from "locked for
 * good", since `shouldShowRequestPermissionRationale` says `false` in both of those opposite
 * situations.
 *
 * Exists as a value (and as the `readPermission` seam both prompts take) because neither answer
 * can be faked on a device any other way: a permission cannot be un-granted for a test — `pm
 * revoke` kills the app's process, instrumentation included — so without the seam every gate
 * behind it is unreachable on a device that has ever run with the permission granted.
 *
 * Shared by the notification prompt on the library screen and the local-network prompt on
 * settings; the two ask for different permissions with the same machinery.
 */
internal data class PermissionReadout(val granted: Boolean, val shouldShowRationale: Boolean)

/**
 * The real answers for [permission]. Asking about one the platform has never heard of is
 * meaningless but safe — it is the caller's `permissionExists` gate that keeps the question
 * sensible, not a platform requirement to annotate: everything here goes through the compat
 * layer, which answers "denied" and "no rationale" for an unknown permission rather than
 * crashing. With that gate a plain parameter, lint could not verify a `@RequiresApi` here anyway —
 * the `@ChecksSdkIntAtLeast` chain only holds while the API level is checked with a constant in
 * sight.
 */
internal fun Activity.permissionReadout(permission: String): PermissionReadout = PermissionReadout(
    granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED,
    shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, permission),
)
