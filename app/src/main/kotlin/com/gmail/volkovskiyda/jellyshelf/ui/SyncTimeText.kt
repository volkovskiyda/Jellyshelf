package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.util.SyncTime
import com.gmail.volkovskiyda.jellyshelf.util.syncTimeOf
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * A sync time as text: "just now", "12 minutes ago", "2 hours ago", or the exact stamp past the
 * three-hour cutover. Null when there is nothing to show, so callers omit the line.
 *
 * The decision lives in [syncTimeOf], which is pure and host-testable; this is only the part that
 * needs resources — the relative cases are plurals, and the app has no hardcoded UI strings.
 */
@Composable
fun formatSyncTime(epochMillis: Long, now: Long): String? =
    when (val time = syncTimeOf(epochMillis, now)) {
        null -> null
        SyncTime.JustNow -> stringResource(R.string.synced_just_now)
        is SyncTime.Minutes ->
            pluralStringResource(R.plurals.synced_minutes_ago, time.minutes, time.minutes)
        is SyncTime.Hours ->
            pluralStringResource(R.plurals.synced_hours_ago, time.hours, time.hours)
        is SyncTime.Absolute -> time.timestamp
    }

/**
 * The current time, re-read once a minute while the screen is resumed.
 *
 * A relative label is only honest if it keeps up: without this, a screen left open would still say
 * "5 minutes ago" an hour later — the exact objection the old absolute-only rule was written to
 * avoid. A minute is the finest granularity any label here shows, so a faster tick would recompose
 * for nothing, and pausing with the lifecycle means a backgrounded screen costs nothing at all.
 *
 * Fixed values are still passed directly by previews and tests, which is why this is a separate
 * function rather than something [formatSyncTime] does for itself.
 */
@Composable
fun rememberNow(time: TimeProvider = koinInject()): State<Long> {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Seeded once so the first frame has a real value rather than 0 (which reads as "never").
    val initial = remember(time) { mutableLongStateOf(time.now()) }
    return produceState(initial.longValue, lifecycleOwner, time) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = time.now()
                delay(TICK_MS)
            }
        }
    }
}

private const val TICK_MS = 60_000L
