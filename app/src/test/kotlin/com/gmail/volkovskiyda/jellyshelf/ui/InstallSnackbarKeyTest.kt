package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallStage
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateCheckError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * That a whole download is *one* snackbar.
 *
 * `showSnackbar` suspends until its snackbar is gone, so whatever the effect keys on decides how
 * often one is torn down and animated back in. Keying on the message meant every progress tick did
 * that — visibly. No assertion about rendered text can catch a regression here, because the text is
 * correct either way; only the key can be pinned, so it is.
 */
class InstallSnackbarKeyTest {

    private fun downloading(bytes: Long) =
        InstallState.Running(InstallStage.DOWNLOADING, bytesDownloaded = bytes, totalBytes = 1024)

    /** The tick-by-tick case that caused the flicker. */
    @Test
    fun everyDownloadTick_sharesOneKey() {
        val keys = (0L..1024L step 64).map { installSnackbarKey(downloading(it)) }

        assertEquals(1, keys.distinct().size, "A download must not re-show the snackbar: $keys")
    }

    /** And so does the move between stages — it is still the same install being reported. */
    @Test
    fun everyRunningStage_sharesOneKey() {
        val keys = listOf(
            InstallState.Running(InstallStage.PREPARING),
            downloading(512),
            InstallState.Running(InstallStage.INSTALLING),
        ).map { installSnackbarKey(it) }

        assertEquals(1, keys.distinct().size, "Stages are one narration, not three: $keys")
    }

    /** A failure is a different subject, so it does get its own snackbar. */
    @Test
    fun aFailure_getsItsOwnKey() {
        assertNotEquals(
            installSnackbarKey(downloading(512)),
            installSnackbarKey(InstallState.Failed(UpdateCheckError.DownloadFailed)),
        )
    }

    /** Nothing to show has no key at all, which is what dismisses the snackbar. */
    @Test
    fun noInstall_hasNoKey() {
        assertNull(installSnackbarKey(null))
    }
}
