package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * Whether the window is at least Material's *medium* width — 600 dp, the line under which a window
 * is "compact": a phone held upright, or one pane of a tight split screen.
 *
 * The one breakpoint the layouts here key off, so the tabs move to a rail and the detail screen
 * splits into two panes at the same moment rather than at two subtly different widths. Everything
 * from a phone in landscape up — and a tablet either way up — is on the wide side of it.
 *
 * Decided from the window, not from `Configuration.orientation`: a tablet in portrait is still
 * 800 dp wide, and a phone in a half-screen split is still a phone. `currentWindowAdaptiveInfo`
 * reads the window's own size, so previews and screenshot tests get the layout their declared
 * size asks for without a flag being threaded through.
 */
@Composable
fun isMediumWidthOrWider(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
