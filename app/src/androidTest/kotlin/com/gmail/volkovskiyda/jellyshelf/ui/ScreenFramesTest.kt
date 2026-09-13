package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.util.RecordingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [ScreenFrames] has to get right is attribution: which visit a frame belongs to, and what a
 * visit reports when it ends. Both fail silently. A frame counted against the wrong screen makes
 * one destination look janky and another clean, with nothing in the app behaving differently; a
 * visit that reports a first-draw time it never measured puts a zero into a trend line that reads
 * as an improvement.
 *
 * Instrumented for the same reason [com.gmail.volkovskiyda.jellyshelf.util.MetricsTest] is: every
 * span opens a system-trace section through `androidx.tracing`, which calls `android.os.Trace`, and
 * this project deliberately has no Robolectric. Nothing here needs a window or a real frame — the
 * frame callback and the clock are both driven by hand.
 */
@RunWith(AndroidJUnit4::class)
class ScreenFramesTest {

    private val recorder = RecordingMetrics()
    private val frames = ScreenFrames(
        metrics = recorder.metrics,
        buildInfo = BuildInfo(isDebug = false, sdkInt = 36),
        elapsedRealtime = { recorder.elapsed },
    )

    @Test
    fun aVisitReportsItsFramesAndFirstDraw() {
        val visit = frames.newVisit(AppNavKey.Library)

        frames.begin(visit)
        recorder.elapsed = 16
        visit.firstDrawn()
        frames.onFrame(durationNanos = 8_000_000, isJank = false)
        frames.onFrame(durationNanos = 40_000_000, isJank = true)
        frames.onFrame(durationNanos = 900_000_000, isJank = true)
        recorder.elapsed = 4_000
        frames.end(visit)

        assertEquals(listOf("screen_library"), recorder.reported())
        assertEquals(
            mapOf(
                "frames" to 3L,
                "jank_frames" to 2L,
                "frozen_frames" to 1L,
                "first_draw_ms" to 16L,
            ),
            recorder.trace("screen_library").metrics,
        )
        // The trace's own duration is how long the screen was composed, not how long it took to draw.
        assertEquals(listOf("screen_library 4000 ms"), recorder.logs)
    }

    @Test
    fun aFrameCountsForEveryVisitOpenWhenItArrives() {
        val leaving = frames.newVisit(AppNavKey.Library)
        val arriving = frames.newVisit(AppNavKey.Categories)

        frames.begin(leaving)
        frames.onFrame(durationNanos = 8_000_000, isJank = false)
        // Mid-transition: both entries are composed and both are on the glass.
        frames.begin(arriving)
        frames.onFrame(durationNanos = 8_000_000, isJank = true)
        frames.end(leaving)
        frames.onFrame(durationNanos = 8_000_000, isJank = false)
        frames.end(arriving)

        assertEquals(2L, recorder.trace("screen_library").metrics["frames"])
        assertEquals(1L, recorder.trace("screen_library").metrics["jank_frames"])
        assertEquals(2L, recorder.trace("screen_categories").metrics["frames"])
        assertEquals(1L, recorder.trace("screen_categories").metrics["jank_frames"])
    }

    @Test
    fun anEndedVisitStopsCounting() {
        val visit = frames.newVisit(AppNavKey.Detail("abc"))

        frames.begin(visit)
        frames.end(visit)
        frames.onFrame(durationNanos = 900_000_000, isJank = true)

        assertEquals(0L, recorder.trace("screen_detail").metrics["frames"])
        assertEquals(0L, recorder.trace("screen_detail").metrics["frozen_frames"])
    }

    @Test
    fun aVisitThatNeverDrewReportsNoFirstDraw() {
        val visit = frames.newVisit(AppNavKey.Player("abc"))

        frames.begin(visit)
        frames.end(visit)

        // Absent, not zero: a zero here would read as an instant screen in the console's trend.
        assertFalse(recorder.trace("screen_player").metrics.containsKey("first_draw_ms"))
        assertEquals(listOf("screen_player"), recorder.reported())
    }

    @Test
    fun theFirstDrawIsTheFirstOne() {
        val visit = frames.newVisit(AppNavKey.CategoryVideos(categoryId = "c1", title = "Channel"))

        frames.begin(visit)
        recorder.elapsed = 21
        visit.firstDrawn()
        recorder.elapsed = 900
        visit.firstDrawn()
        frames.end(visit)

        assertEquals(21L, recorder.trace("screen_category_videos").metrics["first_draw_ms"])
    }

    @Test
    fun eachScreenReportsUnderItsOwnName() {
        listOf(
            AppNavKey.Library to "screen_library",
            AppNavKey.Categories to "screen_categories",
            AppNavKey.Settings to "screen_settings",
            AppNavKey.CategoryVideos("c1", "Channel") to "screen_category_videos",
            AppNavKey.Detail("abc") to "screen_detail",
            AppNavKey.Player("abc") to "screen_player",
        ).forEach { (key, id) ->
            val visit = frames.newVisit(key)
            frames.begin(visit)
            frames.end(visit)
            assertEquals(id, recorder.traces.last().id)
        }
    }
}
