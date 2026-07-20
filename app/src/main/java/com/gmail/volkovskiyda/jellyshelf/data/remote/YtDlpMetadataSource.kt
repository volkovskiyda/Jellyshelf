package com.gmail.volkovskiyda.jellyshelf.data.remote

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-app YouTube metadata fetcher backed by the bundled yt-dlp (youtubedl-android). It produces the
 * same [IndexEntry] shape as jellyshelf-index.json so both metadata sources flow through one merge
 * path in the repository and yield an identical experience.
 *
 * The Python runtime is unpacked lazily on first use — a one-time, multi-second cost — guarded so
 * concurrent callers initialise exactly once. All work runs on [Dispatchers.IO].
 */
class YtDlpMetadataSource(context: Context) {
    private val appContext = context.applicationContext
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    private suspend fun ensureInit() {
        if (initialized) return
        initMutex.withLock {
            if (!initialized) {
                YoutubeDL.getInstance().init(appContext)
                initialized = true
            }
        }
    }

    /**
     * Best-effort yt-dlp self-update so extractors stay current without an app release. Not run
     * automatically — exposed for a future "Update yt-dlp" action.
     */
    suspend fun update(): Unit = withContext(Dispatchers.IO) {
        ensureInit()
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(appContext) }
    }

    /**
     * Fetch metadata for [youtubeId] as an [IndexEntry]. Downloads nothing — dumps the info JSON
     * only. Throws (YoutubeDLException / IO) when extraction fails; callers surface that.
     */
    suspend fun fetch(youtubeId: String): IndexEntry = withContext(Dispatchers.IO) {
        ensureInit()
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$youtubeId").apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        YoutubeDL.getInstance().getInfo(request).toIndexEntry(youtubeId)
    }
}

/**
 * Map yt-dlp's [VideoInfo] onto [IndexEntry], mirroring the field choices of build-library-index.sh
 * (channel falls back to uploader, ids likewise) so in-app and script metadata are interchangeable.
 */
private fun VideoInfo.toIndexEntry(youtubeId: String) = IndexEntry(
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
