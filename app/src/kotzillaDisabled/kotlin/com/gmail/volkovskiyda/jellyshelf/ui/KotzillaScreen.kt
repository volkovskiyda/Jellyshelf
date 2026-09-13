package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey

/**
 * Stands in for the real screen registration when `app/kotzilla.json` is absent, and is compiled
 * only then — see the source-set `if` in `app/build.gradle.kts`.
 *
 * Composes its content and nothing else. The twin in `src/kotzillaEnabled` explains what that
 * content is registered as when the keys are there, and why the call has to be hand-written at all.
 *
 * Deliberately references no Kotzilla type, and must stay signature-identical to that twin: no
 * build compiles both, so only running both compiles proves they still match.
 */
@Suppress("UnusedParameter")
@Composable
internal fun KotzillaScreen(key: AppNavKey, content: @Composable () -> Unit) {
    content()
}
