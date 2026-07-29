package com.gmail.volkovskiyda.jellyshelf.data.remote

import android.content.Context
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * In-app YouTube metadata fetcher backed by the bundled yt-dlp (youtubedl-android). It produces the
 * same [IndexEntry] shape as jellyshelf-index.json so both metadata sources flow through one merge
 * path in the repository and yield an identical experience.
 *
 * The Python runtime is unpacked lazily on first use — a one-time, multi-second cost — guarded so
 * concurrent callers initialise exactly once. All work runs on the injected IO dispatcher.
 */
open class YtDlpMetadataSource(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext
    private val initMutex = Mutex()

    private val initialized = MutableStateFlow(false)

    private suspend fun ensureInit() {
        if (initialized.value) return
        initMutex.withLock {
            if (!initialized.value) {
                YoutubeDL.getInstance().init(appContext)
                initialized.value = true
            }
        }
    }

    /**
     * Best-effort yt-dlp self-update so extractors stay current without an app release. Not run
     * automatically — exposed for a future "Update yt-dlp" action. Returns whether it succeeded,
     * so that action can report an outcome.
     */
    suspend fun update(): Boolean = withContext(dispatchers.io) {
        ensureInit()
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(appContext) }.isSuccess
    }

    /**
     * Fetch metadata for [youtubeId] as an [IndexEntry]. Downloads nothing — dumps the info JSON
     * only. Throws (YoutubeDLException / IO) when extraction fails or times out; callers surface
     * that. Cancelling the caller kills the extraction rather than leaking a live child process.
     *
     * `open` purely as a test seam: this is the one dependency of `DefaultLibraryRepository` that
     * shells out to a real Python runtime, and the sync suite's auto-fill cases must not launch it.
     */
    open suspend fun fetch(youtubeId: String): IndexEntry {
        withContext(dispatchers.io) { ensureInit() }
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$youtubeId").apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        val info = try {
            withTimeout(FETCH_TIMEOUT) {
                // getInfo blocks the thread on a child Python process, so neither the timeout nor
                // the caller's cancellation can end it on their own: runInterruptible interrupts
                // the waiting thread, and youtubedl-android's InterruptedException path destroys
                // that process before rethrowing. Without it a hung extraction keeps burning CPU
                // and battery long after the user pressed Cancel.
                runInterruptible(dispatchers.io) { YoutubeDL.getInstance().getInfo(request) }
            }
        } catch (e: TimeoutCancellationException) {
            // The timeout is this fetch's own failure, not the caller's cancellation — rethrown as
            // a plain exception so a bulk run counts the video as failed and continues with the next.
            throw YoutubeDLException("yt-dlp timed out after $FETCH_TIMEOUT for $youtubeId", e)
        }
        return info.toIndexEntry(youtubeId)
    }
}

/**
 * Upper bound on a single extraction. yt-dlp answers in a few seconds when YouTube cooperates;
 * past this it is hung (or wedged behind a stalled network read) and the whole bulk fetch would
 * otherwise wait on it forever.
 */
private val FETCH_TIMEOUT = 60.seconds

/**
 * Map yt-dlp's [VideoInfo] onto [IndexEntry], mirroring the field choices of build-library-index.sh
 * (channel falls back to uploader, ids likewise) so in-app and script metadata are interchangeable.
 */
internal fun VideoInfo.toIndexEntry(youtubeId: String) = IndexEntry(
    id = id ?: youtubeId,
    title = title,
    channel = uploader,
    channelId = uploaderId,
    duration = duration.toLong().takeIf { it > 0 },
    uploadDate = uploadDate,
    tags = tags?.toList(),
    categories = categories?.toList(),
    description = description,
    thumbnail = thumbnail,
    // yt-dlp extracted this metadata just now; stamped with the device clock by the repository.
    fetchedAt = null,
)
