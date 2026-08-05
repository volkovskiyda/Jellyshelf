package com.gmail.volkovskiyda.jellyshelf.domain.repository

import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlayMethod
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The library's read/write contract in domain terms. Its single implementation
 * (`data.repository.DefaultLibraryRepository`) orchestrates Room, Jellyfin and yt-dlp and maps
 * their rows/DTOs to the [Video]/[CategoryWithCount] domain models exposed here.
 */
@Suppress("TooManyFunctions") // the app's single library-domain facade
interface LibraryRepository {
    fun observeVideos(): Flow<List<Video>>
    fun searchVideos(query: String, bucket: DurationBucket?): Flow<List<Video>>
    fun observeVideosByCategory(categoryId: String): Flow<List<Video>>
    fun observeVideo(youtubeId: String): Flow<Video?>
    fun observeCategories(): Flow<List<CategoryWithCount>>
    fun searchCategories(query: String): Flow<List<CategoryWithCount>>
    fun observeOthers(): Flow<List<CategoryWithCount>>
    fun videoCount(): Flow<Int>

    val bulkFetch: StateFlow<BulkProgress>
    val bulkRemove: StateFlow<BulkProgress>

    suspend fun sync(): SyncResult
    suspend fun fetchMetadata(youtubeId: String): FetchResult
    fun startFetchMissing()
    fun cancelFetchMissing()
    fun acknowledgeBulkFetch()
    fun startRemoveWatched()
    fun cancelRemoveWatched()
    fun acknowledgeBulkRemove()

    /**
     * Fills the library with the bundled demo dataset — no server, no network — and records that
     * the library is now a demo ([com.gmail.volkovskiyda.jellyshelf.domain.model.Settings.demoMode]).
     * Auto-categories are derived by the same code a real sync uses, so the Categories screen looks
     * exactly as it would after one.
     *
     * Idempotent: rows are keyed by their id, so seeding over an existing demo library replaces it
     * rather than duplicating it. [clearLocalData] is the way out.
     */
    suspend fun seedDemoLibrary()

    suspend fun clearLocalData()
    suspend fun removeVideo(youtubeId: String)
    suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean

    /**
     * Opens a Jellyfin playback session for [youtubeId] — the first of the three reports the in-app
     * player sends (start, then [reportPlaybackProgress], then [reportPlaybackStopped]). Positions
     * reported through those endpoints run through the server's own resume thresholds, so the
     * *server* decides when a video counts as watched (90% by default, configurable per server)
     * instead of this app guessing at it.
     *
     * Sent once the video is genuinely under way rather than the moment the queue moves to it: the
     * server clears `Played` and bumps `PlayCount` at start — its rewatch semantics — which
     * stepping through a queue would otherwise apply to every video passed over.
     *
     * [playSessionId] is what ties this report to the progress and stop reports that follow it —
     * the caller mints one per session, since the /PlaybackInfo call official clients take theirs
     * from is not part of direct play.
     *
     * [playMethod] describes how the server is delivering the stream right now — the app asks for
     * direct play and only reports a transcode when the decode fallback put it on one.
     *
     * Server-only and fire-and-forget: no local write, nothing to wait for.
     */
    fun reportPlaybackStarted(
        youtubeId: String,
        positionMs: Long,
        playSessionId: String?,
        playMethod: PlayMethod,
    )

    /**
     * Reports an in-flight position for the session [reportPlaybackStarted] opened — every few
     * seconds while playing, and once more when playback pauses ([isPaused]). This is what marks a
     * video watched *mid-playback*, the moment a report crosses the server's threshold.
     *
     * Server-only and fire-and-forget: [savePlaybackPosition] stays the one path that writes a
     * position locally.
     */
    fun reportPlaybackProgress(
        youtubeId: String,
        positionMs: Long,
        isPaused: Boolean,
        playSessionId: String?,
        playMethod: PlayMethod,
    )

    /**
     * One report per stop, from either player: the position is stored locally and mirrored to
     * Jellyfin — as a finished watch when [completed] (or within a few seconds of the end),
     * otherwise as a resume point.
     *
     * Which server carrier does the mirroring depends on where playback happened, which a
     * [playSessionId] is exactly the mark of:
     * - With one — the in-app player, which has been reporting progress into that session all
     *   along. The final position closes it, so the server thresholds it exactly as it thresholded
     *   every progress report: past the threshold marks the video watched, short of it becomes a
     *   resume point. The server is never told `Played=false` on this path — that would un-watch a
     *   video its own threshold marked seconds earlier.
     * - Without one — an external player, whose only signal is the result it hands back once
     *   playback is already over. Its position is written to the item's user data directly, no
     *   thresholds.
     *
     * A finished watch additionally goes through the played-items endpoint on both paths: that is
     * what records the play in the server's watch history (PlayCount, LastPlayedDate).
     *
     * A stop barely into a video records **nothing at all**, keeping whatever position the video
     * already held. Stepping through a queue with previous/next passes over videos without
     * watching them, and a resume point a few seconds in is worse than none — it would also
     * overwrite a real one. Fire-and-forget.
     */
    fun reportPlaybackStopped(
        youtubeId: String,
        positionMs: Long,
        completed: Boolean,
        playSessionId: String? = null,
    )

    /**
     * Local-only periodic position save from the in-app player, so process death mid-playback
     * can't lose the spot. No server write of its own — what the server hears rides the session
     * reports ([reportPlaybackProgress]) — and it never unmarks a played video. Fire-and-forget.
     *
     * Starts recording only once the video is far enough in to be worth resuming, on the same bar
     * [reportPlaybackStopped] uses: this runs every few seconds, so without it the trivial
     * position would be on disk regardless of what any stop report decided.
     */
    fun savePlaybackPosition(youtubeId: String, positionMs: Long)
    suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult
}
