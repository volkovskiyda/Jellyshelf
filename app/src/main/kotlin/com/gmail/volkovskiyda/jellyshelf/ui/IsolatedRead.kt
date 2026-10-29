package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * [content] with [state]'s value, read in this scope of its own rather than in the caller's: a new
 * value recomposes this and [content], and leaves the caller alone.
 *
 * For the screens whose list re-emits far more often than anything else on them changes. The
 * browse flows re-emit on every write to the library — every ten seconds while a video plays, when
 * its position is saved, and in a stream during a sync — and read in the screen's own body, each
 * emission re-ran all of it, a dozen flow collections, the ViewModel lookup and every effect
 * included, only to hand one new list to the content below (composition trace, 2026-10-29).
 */
@Composable
fun <T> IsolatedRead(state: State<T>, content: @Composable (T) -> Unit) {
    content(state.value)
}
