package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.LOCAL_NETWORK_PERMISSION_API
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository

/**
 * A plausible instant rather than `0`, for the reason the prompt's own tests give: the store's `0`
 * means "never answered", so a clock at `0` would read a never-answered store as answered this
 * very moment — and [LocalNetworkPrompt.due] would quietly return false for the whole snooze.
 * Anything that used this helper to exercise `due` would pass without asking anything.
 */
private const val TEST_NOW = 1_800_000_000_000L

/**
 * A [LocalNetworkPrompt] over an in-memory store, for tests about something else entirely — every
 * `SettingsViewModel` needs one, and only the prompt's own tests care what it decides.
 *
 * Nothing here is inert on purpose: the prompt shows no UI by itself, and the only thing the
 * ViewModel ever asks of it is [LocalNetworkPrompt.rearm], which writes to the fake store handed
 * in. A test that wants to assert that write passes its own [FakeSettingsRepository] and reads
 * `savedLocalNetworkPrompt` back.
 */
fun testLocalNetworkPrompt(settings: SettingsRepository = FakeSettingsRepository()) = LocalNetworkPrompt(
    settingsRepository = settings,
    time = TimeProvider { TEST_NOW },
    buildInfo = BuildInfo(isDebug = true, sdkInt = LOCAL_NETWORK_PERMISSION_API),
    dispatchers = TestDispatcherProvider(),
)
