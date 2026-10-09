package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * Whether the window is at least Material's *medium* width — 600 dp, the line under which a window
 * is "compact": a phone held upright, or one pane of a tight split screen.
 *
 * The breakpoint the layouts here key off, so the tabs move to a rail and the detail screen splits
 * into two panes at the same moment rather than at two subtly different widths. Everything from a
 * phone in landscape up — and a tablet either way up — is on the wide side of it. The only other
 * check, [isExpandedWindow], changes how big things are drawn, never where they go.
 *
 * Decided from the window, not from `Configuration.orientation`: a tablet in portrait is still
 * 800 dp wide, and a phone in a half-screen split is still a phone. `currentWindowAdaptiveInfoV2`
 * reads the window's own size, so previews and screenshot tests get the layout their declared
 * size asks for without a flag being threaded through.
 */
@Composable
fun isMediumWidthOrWider(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * Whether the window is *expanded* wide (840 dp and up) and at least *medium* tall (480 dp and up):
 * a tablet on its side, or a desktop-sized window.
 *
 * The height half is what keeps phones out. A large phone on its side is 890-odd dp wide — past
 * the expanded line — but only about 410 dp tall, and something drawn bigger for the width would
 * cost it rows it has none to spare of. A tablet held upright is 800 dp wide, still medium.
 */
@Composable
fun isExpandedWindow(): Boolean {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) &&
        sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
}
