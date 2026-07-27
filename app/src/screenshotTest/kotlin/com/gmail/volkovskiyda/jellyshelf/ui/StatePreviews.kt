package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// The two whole-list placeholders. Both are shown in place of content, so their goldens are
// sized like a screen rather than a row.

@PreviewTest
@Preview(widthDp = 400, heightDp = 500, showBackground = true)
@Composable
private fun EmptyStateLibrary() {
    PreviewTheme { EmptyState("No videos yet. Configure Jellyfin in Settings and sync.") }
}

@PreviewTest
@Preview(widthDp = 400, heightDp = 500, showBackground = true)
@Composable
private fun EmptyStateNoMatch() {
    PreviewTheme { EmptyState("No videos match \"mafia\"") }
}

@PreviewTest
@Preview(widthDp = 400, heightDp = 500, showBackground = true)
@Composable
private fun EmptyStateDark() {
    PreviewTheme(darkTheme = true) { EmptyState("No videos match \"mafia\"") }
}

// The spinner is animated; LayoutLib renders the first frame, which is what makes this golden
// stable. If it ever starts flaking, that assumption is what broke.
@PreviewTest
@Preview(widthDp = 400, heightDp = 500, showBackground = true)
@Composable
private fun LoadingStateDefault() {
    PreviewTheme { LoadingState() }
}
