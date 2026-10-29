package com.gmail.volkovskiyda.jellyshelf.ui

import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks

/**
 * [enableAccessibilityChecks] from API 34 up, and nothing below.
 *
 * The library declares `@RequiresApi(34)` and checks nothing at runtime, and lint does not look at
 * test sources, so calling it directly ran the Accessibility Test Framework on Test Lab's API 31
 * and 33 devices too — levels Compose does not support the checks on, where a pass proves nothing
 * and a failure would be noise. The tests that call this still run their own assertions on every
 * device; only the checks are limited to where they mean something.
 */
fun ComposeTestRule.enableAccessibilityChecksWhereSupported() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) enableAccessibilityChecks()
}
