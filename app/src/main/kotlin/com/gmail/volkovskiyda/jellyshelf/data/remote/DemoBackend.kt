package com.gmail.volkovskiyda.jellyshelf.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The server and the yt-dlp runtime that demo mode doesn't have.
 *
 * Demo installs hold no credentials, so every real network path already declines on its own — but
 * declining is not the same as working. Before this existed, "Remove N watched videos" reported
 * every video as failed without attempting anything, and "Fetch metadata for N missing" unpacked
 * the bundled Python runtime and asked youtube.com about video ids that cannot exist. A showcase
 * install must not do either: the actions have to *do something*, and they have to do it without
 * a network.
 *
 * So each call here takes a plausible amount of time, fails a fraction of the time
 * ([FAILURE_RATE]), and otherwise succeeds with data from the bundled assets. Failures are
 * deliberately indistinguishable from real ones — real yt-dlp stderr, real Jellyfin status lines —
 * because the error states are part of what the demo is showing; the Settings screen is where the
 * app says it is a demo.
 *
 * The dice are rolled per call rather than per video, so a retry of a failed video can succeed.
 * That is also why nothing here is deterministic: a demo where the same video always fails would
 * make the retry affordance a lie.
 */
open class DemoBackend(private val indexSource: IndexSource) {

    /**
     * The demo dataset keyed by video id: [IndexSource.demoFetchedEntries] first, so the bare
     * entries resolve to the metadata a fetch is supposed to discover, then
     * [IndexSource.demoEntries] so re-fetching an already-described video finds its own row.
     *
     * Cached because assets can't change under a running process, and a bulk fetch would otherwise
     * re-parse both documents once per video. `MutableStateFlow` + [Mutex] rather than a plain
     * field: concurrent callers must parse exactly once, and the app keeps its shared mutable
     * slots in state flows.
     */
    private val entries = MutableStateFlow<Map<String, IndexEntry>?>(null)
    private val entriesMutex = Mutex()

    private suspend fun entries(): Map<String, IndexEntry> = entries.value ?: entriesMutex.withLock {
        entries.value ?: buildMap {
            indexSource.demoEntries().forEach { put(it.id, it) }
            indexSource.demoFetchedEntries().forEach { put(it.id, it) }
        }.also { entries.value = it }
    }

    /**
     * Stands in for [YtDlpMetadataSource.fetch]: the metadata the bundled dataset holds for
     * [youtubeId], after a pause of about the length a real extraction takes.
     *
     * Throws exactly as the real fetcher does, with yt-dlp's own wording — the repository stamps
     * the message onto the row, and the detail screen shows it.
     */
    open suspend fun fetchMetadata(youtubeId: String): IndexEntry {
        pause(FETCH_LATENCY)
        if (failed()) throw IOException(fetchErrors(youtubeId).random())
        // A video the dataset never described is indistinguishable from one YouTube has taken
        // down, so it reports as that rather than as a broken demo.
        return entries()[youtubeId] ?: throw IOException(videoUnavailable(youtubeId))
    }

    /**
     * Stands in for a Jellyfin item delete. Returns normally when the fake server accepted it — the
     * repository then drops the local row, exactly as it does for a confirmed real delete.
     */
    open suspend fun deleteItem() {
        pause(DELETE_LATENCY)
        if (failed()) throw IOException(DELETE_ERRORS.random())
    }

    /** Stands in for creating a Jellyfin playlist. Nothing is stored: the app never reads one back. */
    open suspend fun createPlaylist() {
        pause(PLAYLIST_LATENCY)
        if (failed()) throw IOException(PLAYLIST_ERROR)
    }

    /**
     * The pause a demo sync takes before the repository re-derives its categories. Never fails:
     * sync is the first thing a demo user taps, and its per-video auto-fill pass already supplies
     * the failure realism.
     */
    open suspend fun sync() = pause(SYNC_LATENCY)

    /**
     * Whether this call fails. `open` purely as a test seam — the same reason
     * [YtDlpMetadataSource.fetch] is open: a test that cannot pin the dice can only assert what is
     * true of both outcomes.
     */
    protected open fun failed(): Boolean = Random.Default.nextFloat() < FAILURE_RATE

    /**
     * Simulated round-trip time. A test seam as well: instrumented tests run on real dispatchers,
     * where a run over a handful of videos would otherwise spend real seconds asleep.
     */
    protected open suspend fun pause(range: ClosedRange<Duration>) {
        delay(range.start + (range.endInclusive - range.start) * Random.Default.nextDouble())
    }

    private fun videoUnavailable(youtubeId: String) =
        "ERROR: [youtube] $youtubeId: Video unavailable. This video has been removed by the uploader"

    /** Verbatim yt-dlp stderr, minus the traceback — what the real fetcher's exception carries. */
    private fun fetchErrors(youtubeId: String) = listOf(
        videoUnavailable(youtubeId),
        "ERROR: [youtube] $youtubeId: Private video. Sign in if you've been granted access to this video",
        "ERROR: [youtube] $youtubeId: Sign in to confirm you're not a bot. Use --cookies-from-browser",
        "ERROR: [youtube] $youtubeId: Unable to download API page: HTTP Error 429: Too Many Requests",
    )
}

/**
 * How often a demo call fails. High enough that a bulk run over a handful of videos usually shows
 * at least one failure — the "N failed" summary and the red per-video error line are states worth
 * demonstrating — and low enough that the happy path is what a viewer mostly sees.
 */
private const val FAILURE_RATE = 0.2f

// Roughly what each operation costs against a real server / a real extraction: long enough that
// the progress bar is legible, short enough that nobody waits through a demo.
private val FETCH_LATENCY = 400.milliseconds..1_200.milliseconds
private val DELETE_LATENCY = 150.milliseconds..500.milliseconds
private val PLAYLIST_LATENCY = 400.milliseconds..900.milliseconds
private val SYNC_LATENCY = 600.milliseconds..1_500.milliseconds

/** What Jellyfin refuses a delete with: no rights, already gone, or a bad moment. */
private val DELETE_ERRORS = listOf(
    "Server returned 403 Forbidden",
    "Server returned 404 Not Found",
    "Server returned 500 Internal Server Error",
)

private const val PLAYLIST_ERROR = "Server returned 500 Internal Server Error"
