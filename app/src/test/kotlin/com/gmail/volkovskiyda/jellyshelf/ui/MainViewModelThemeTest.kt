package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeMode
import com.gmail.volkovskiyda.jellyshelf.domain.model.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The theme override [MainActivity][com.gmail.volkovskiyda.jellyshelf.MainActivity] reads to pick
 * the color scheme and the system-bar styling. Resolving the *effective* dark flag is
 * `ThemeMode.isDark()`, which reads `isSystemInDarkTheme()` and so belongs to composition — the
 * behavior test for it is instrumented, per this project's no-Robolectric convention.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelThemeTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Null first: the activity must not paint a forced theme before the stored one is read. */
    @Test
    fun `the theme is null until the stored value lands`() = runTest {
        assertNull(MainViewModel(FakeSettingsRepository()).themeState.value)
    }

    @Test
    fun `a stored override surfaces with its direction intact`() = runTest {
        val stored = ThemeState(ThemeMode.DARK, towardDark = false)
        val repo = FakeSettingsRepository(themeState = stored)

        assertEquals(stored, MainViewModel(repo).themeState.filterNotNull().first())
    }

    /** A fresh install follows the system, exactly as the app did before the setting existed. */
    @Test
    fun `an install that never set a theme reports auto`() = runTest {
        val state = MainViewModel(FakeSettingsRepository()).themeState.filterNotNull().first()

        assertEquals(ThemeMode.AUTO, state.mode)
    }

    /** The activity collects this flow, so a change written elsewhere has to reach it. */
    @Test
    fun `a later write reaches the collected state`() = runTest {
        val repo = FakeSettingsRepository()
        val viewModel = MainViewModel(repo)
        viewModel.themeState.filterNotNull().first()

        repo.setThemeState(ThemeState(ThemeMode.LIGHT, towardDark = true))

        assertEquals(
            ThemeMode.LIGHT,
            viewModel.themeState.filterNotNull().first { it.mode == ThemeMode.LIGHT }.mode,
        )
    }
}
