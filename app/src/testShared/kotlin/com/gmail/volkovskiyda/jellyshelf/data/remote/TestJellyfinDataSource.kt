package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.data.repository.JellyfinDataSource
import com.gmail.volkovskiyda.jellyshelf.domain.DeviceInfo
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.FakeSettingsRepository
import io.ktor.client.HttpClient

/** What a test client calls itself in the `MediaBrowser` authorization header. */
val testDeviceInfo = DeviceInfo(
    clientName = "Jellyshelf Android",
    deviceName = "Test Device",
    version = "0.0.0-test",
)

/**
 * A [JellyfinClient] over [httpClient] — usually a MockEngine one. [settings] is read for nothing
 * but the install's device id, so the default fake suits any test not asserting on that field.
 */
fun testJellyfinClient(
    httpClient: HttpClient,
    settings: SettingsRepository = FakeSettingsRepository(),
): JellyfinClient = JellyfinClient(httpClient, settings, testDeviceInfo)

/** A [JellyfinDataSource] over [httpClient], wired like [testJellyfinClient]. */
fun testJellyfinDataSource(
    httpClient: HttpClient,
    settings: SettingsRepository = FakeSettingsRepository(),
): JellyfinDataSource = JellyfinDataSource(testJellyfinClient(httpClient, settings))
