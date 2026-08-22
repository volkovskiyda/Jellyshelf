package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.domain.LOCAL_NETWORK_PERMISSION_API
import com.gmail.volkovskiyda.jellyshelf.domain.LocalNetworkPrompt
import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository

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
    time = TimeProvider { 0L },
    buildInfo = BuildInfo(isDebug = true, sdkInt = LOCAL_NETWORK_PERMISSION_API),
    dispatchers = TestDispatcherProvider(),
)
