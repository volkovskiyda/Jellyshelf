package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.ZoneId

/**
 * The zone every absolute stamp in the UI is rendered in — upload times and the sync time past
 * its relative window. The device's own zone, which is the only right answer for a user; it is a
 * composition local so the screenshot previews can pin UTC instead. A golden rendered in the
 * generating machine's zone would disagree with CI's by whatever the two clocks are apart, and
 * the pinned fixtures are pointless if the reference image still depends on where it was made.
 *
 * Static because it never changes within a composition: the app never provides it, and a preview
 * provides it once at the root.
 */
val LocalZoneId = staticCompositionLocalOf<ZoneId> { ZoneId.systemDefault() }
