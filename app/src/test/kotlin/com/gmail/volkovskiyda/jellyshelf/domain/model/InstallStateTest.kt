package com.gmail.volkovskiyda.jellyshelf.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the install has a number worth showing, and when claiming one would be a lie.
 *
 * The SDK reports `apkFileTotalBytes = 0` until the transfer actually starts, so the difference
 * between "0%" and "no percentage yet" is the difference between a progress bar that looks stuck
 * and one that is honestly indeterminate.
 */
class InstallStateTest {

    @Test
    fun downloading_reportsTheFraction() {
        val state = InstallState.Running(InstallStage.DOWNLOADING, bytesDownloaded = 512, totalBytes = 1024)

        assertEquals(0.5f, state.fraction)
    }

    @Test
    fun anUnknownTotal_hasNoFraction() {
        val state = InstallState.Running(InstallStage.DOWNLOADING, bytesDownloaded = 0, totalBytes = 0)

        assertNull(state.fraction)
    }

    /** Preparing has no bytes yet, and installing is past them — neither is a percentage. */
    @Test
    fun theOtherStages_haveNoFraction() {
        assertNull(InstallState.Running(InstallStage.PREPARING).fraction)
        assertNull(InstallState.Running(InstallStage.INSTALLING).fraction)
    }

    /**
     * A total that is stale by a chunk, or a resumed download counted twice, must not produce a
     * progress bar past its own end.
     */
    @Test
    fun moreBytesThanExpected_clampsToComplete() {
        val state = InstallState.Running(InstallStage.DOWNLOADING, bytesDownloaded = 2048, totalBytes = 1024)

        assertEquals(1f, state.fraction)
    }
}
