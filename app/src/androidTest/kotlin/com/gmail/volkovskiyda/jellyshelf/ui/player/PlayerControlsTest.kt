package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavior tests for the player controls' speed menu — stateless content, so the same harness
 * as DetailContentTest: instrumented because input dispatch needs a real Android runtime (no
 * Robolectric in this codebase by decision), and `connectedDebugAndroidTest` skips itself when
 * no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setControls(speed: Float = 1f, onSetSpeed: (Float) -> Unit = {}) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false, buildInfo = BuildInfo(isDebug = true, sdkInt = 36)) {
                PlayerControls(
                    title = "Sample video",
                    showPlay = false,
                    positionMs = 10_000L,
                    durationMs = 60_000L,
                    chapters = emptyList(),
                    speed = speed,
                    onPlayPause = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    onSeek = {},
                    onSetSpeed = onSetSpeed,
                    onScrubbingChanged = {},
                    onSpeedMenuChanged = {},
                    onOpenChapters = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun speedChip_showsTheCurrentSpeed_andTheMenuStartsClosed() {
        setControls(speed = 1.5f)

        composeRule.onNodeWithText("1.5×").assertIsDisplayed()
        composeRule.onNodeWithText("0.5×").assertDoesNotExist()
    }

    @Test
    fun pickingASpeed_appliesIt_andClosesTheMenu() {
        var applied: Float? = null
        setControls(onSetSpeed = { applied = it })

        composeRule.onNodeWithText("1×").performClick()
        composeRule.onNodeWithText("1.5×").performClick()

        assertEquals(1.5f, applied)
        // The menu is gone: none of the other options remain on screen.
        composeRule.onNodeWithText("0.75×").assertDoesNotExist()
    }
}
