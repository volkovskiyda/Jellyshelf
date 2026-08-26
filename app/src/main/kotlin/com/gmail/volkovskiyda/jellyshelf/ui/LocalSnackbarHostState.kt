package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's one snackbar host, provided by `MainActivity`'s Scaffold so any screen inside it can
 * post a message without that host being threaded down through every navigation entry.
 *
 * The default is a real but orphaned [SnackbarHostState] rather than an `error(...)`: content
 * composables are rendered on their own by previews, screenshot goldens and the behavior tests,
 * with no Scaffold above them, and a screen must not crash for wanting to post a snackbar in a
 * place that has nowhere to show one. Nothing displays it, and the `showSnackbar` call suspends
 * until its caller is cancelled — which for a one-shot effect is exactly the same as not showing.
 */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }
