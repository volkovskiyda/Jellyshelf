package com.gmail.volkovskiyda.jellyshelf.data.repository

import androidx.room.withTransaction
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.JellyshelfDatabase
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoCategoryCrossRef
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.data.mapper.toDomain
import com.gmail.volkovskiyda.jellyshelf.data.remote.BaseItemDto
import com.gmail.volkovskiyda.jellyshelf.data.remote.DemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexEntry
import com.gmail.volkovskiyda.jellyshelf.data.remote.toChapters
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.BulkProgress
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.DEMO_ITEM_ID
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.FetchResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_INDEX
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_JELLYFIN
import com.gmail.volkovskiyda.jellyshelf.domain.model.METADATA_SOURCE_YTDLP
import com.gmail.volkovskiyda.jellyshelf.domain.model.PlaylistResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Settings
import com.gmail.volkovskiyda.jellyshelf.domain.model.SyncResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_CONTINUE
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_UNCATEGORIZED
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_UNWATCHED
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_WATCHED
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.util.CLEARTEXT_BLOCKED_MESSAGE
import com.gmail.volkovskiyda.jellyshelf.util.SESSION_EXPIRED_MESSAGE
import com.gmail.volkovskiyda.jellyshelf.util.YoutubeId
import com.gmail.volkovskiyda.jellyshelf.util.escapeLikePattern
import com.gmail.volkovskiyda.jellyshelf.util.isCleartextBlocked
import com.gmail.volkovskiyda.jellyshelf.util.isUnauthorized
import com.gmail.volkovskiyda.jellyshelf.util.millisToTicks
import com.gmail.volkovskiyda.jellyshelf.util.runCatchingCancellable
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import com.gmail.volkovskiyda.jellyshelf.util.ticksToSeconds
import com.gmail.volkovskiyda.jellyshelf.util.yearMonthOf
import com.gmail.volkovskiyda.jellyshelf.util.yearOf
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

private val HTTP_CLIENT_ERRORS = 400..499
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429

/** 4xx means the request itself is wrong (bad key, deleted user/folder) — except the
 *  explicitly transient 408 (timeout) and 429 (throttling). Under Ktor's `expectSuccess = true`,
 *  a non-2xx surfaces as [ResponseException] (Client/ServerResponseException); timeouts throw
 *  HttpRequestTimeoutException instead, so they fall through to transient.
 *
 *  A cleartext block is permanent too, and doesn't arrive as a [ResponseException] at all: the
 *  platform rejects the request before it reaches the server. Retrying an `http://` URL on a
 *  release build burns battery every period and can never succeed. */
internal fun isPermanentFailure(e: Throwable): Boolean {
    if (isCleartextBlocked(e)) return true
    val code = (e as? ResponseException)?.response?.status?.value
    return code != null && code in HTTP_CLIENT_ERRORS &&
        code != HTTP_REQUEST_TIMEOUT && code != HTTP_TOO_MANY_REQUESTS
}

/** Shared logcat tag for the external-player / playstate flow: `adb logcat -s Playback`. Kept in
 *  the data layer so it doesn't depend on the Android-heavy `util.Playback`. */
private const val PLAYBACK_TAG = "Playback"

/** Stopping within this many seconds of the end counts as a finished watch, not a resume point. */
private const val COMPLETION_THRESHOLD_SECONDS = 5L

/**
 * How many consecutive syncs may miss a video before it is deleted locally. At the sync worker's
 * 3h cadence that is roughly 9h of continuous absence — long enough to ride out a Jellyfin library
 * rescan or a paging hiccup, short enough that genuinely deleted videos don't linger for days.
 */
private const val MAX_MISSED_SYNCS = 3

/**
 * A listing returning less than this fraction of the videos already stored is not trusted to
 * describe the library, so that sync prunes nothing at all — not even a missed-sync increment.
 */
private const val MIN_TRUSTED_LISTING_RATIO = 0.5

/**
 * Sync auto-fills metadata gaps with yt-dlp only while *fewer* than this many videos lack it. A
 * bigger gap is a bulk job the user starts deliberately from the Uncategorized filter, where it
 * reports progress and can be cancelled.
 */
private const val AUTO_FILL_MAX_MISSING = 10

/**
 * Total budget for a sync's auto-fill pass. The sync runs inside a WorkManager worker with a
 * ~10 minute execution window, and up to nine extractions at yt-dlp's own 60 s ceiling could
 * otherwise consume nearly all of it.
 */
private val AUTO_FILL_BUDGET = 5.minutes

/** Fallback text for a yt-dlp failure that arrived without a message. */
private const val FETCH_FAILED_ERROR = "yt-dlp failed to fetch metadata."

/** The row went away between the fetch and the write — not a fetch failure. */
private const val VIDEO_NOT_FOUND_ERROR = "Video not found locally."

/** Every nth seeded video is watched, and every mth is left part-watched. Coprime, so both land. */
private const val DEMO_PLAYED_EVERY = 4
private const val DEMO_IN_PROGRESS_EVERY = 7

/** How far into a part-watched demo video its resume point sits. */
private const val DEMO_RESUME_PERCENT = 40
private const val PERCENT = 100

private const val MILLIS_PER_SECOND = 1_000L

/** The watch state one seeded row starts life with. */
private class DemoWatchState(val played: Boolean, val positionTicks: Long, val playCount: Int)

/**
 * Whether a demo entry carries any of the metadata an index match supplies. Entries with none are
 * seeded as Jellyfin-sourced — see [DefaultLibraryRepository.demoVideo].
 */
internal val IndexEntry.hasIndexMetadata: Boolean
    get() = channel != null || duration != null || uploadDate != null ||
        description != null || !categories.isNullOrEmpty() || !tags.isNullOrEmpty()

/** What a sync does with the stored videos its server listing didn't contain. */
internal enum class Prune {
    /** Delete them now — the user re-scoped the library, so their absence is deliberate. */
    IMMEDIATE,

    /** Count a miss; delete only after [MAX_MISSED_SYNCS] of them. The normal path. */
    GRACE,

    /** Leave them completely alone — the listing is too short to be believed. */
    NOTHING,
}

/**
 * Picks the prune policy for a sync that saw [seenCount] of the [storedCount] videos already in
 * the database.
 *
 * A listing far shorter than the library usually means the server is mid-rescan (or paging
 * returned a partial view), not that most videos were deleted, so those syncs prune nothing —
 * not even a missed-sync increment, which would otherwise let three flaky syncs in a row delete
 * the whole library. A scope change is the one case where a shorter listing is expected and the
 * deletions are what the user asked for.
 */
internal fun prunePolicy(scopeChanged: Boolean, storedCount: Int, seenCount: Int): Prune = when {
    scopeChanged -> Prune.IMMEDIATE
    seenCount >= storedCount * MIN_TRUSTED_LISTING_RATIO -> Prune.GRACE
    else -> Prune.NOTHING
}

/**
 * Drives one cancellable bulk run and publishes its [BulkProgress]. Both bulk actions (fetch all
 * missing metadata, remove all watched) get their own instance, so starting or cancelling one
 * leaves the other alone.
 */
private class BulkRunner(private val scope: CoroutineScope) {
    private val _progress = MutableStateFlow<BulkProgress>(BulkProgress.Idle)
    val progress: StateFlow<BulkProgress> = _progress.asStateFlow()

    /**
     * Guards every read-modify-write of [job], [generation] and the [_progress] state. Start and
     * cancel both check-then-act on the current job, and are called straight from the UI rather
     * than from a coroutine, so a plain lock (not a Mutex) is what fits.
     */
    private val lock = Any()
    private var job: Job? = null

    /**
     * Bumped by every start and cancel. A run only publishes progress for its own generation, so
     * a `Running` write that was already in flight when the user pressed Cancel can't land after
     * the reset to `Idle` and strand the UI on a job that no longer exists.
     */
    private var generation = 0L

    val isActive: Boolean get() = synchronized(lock) { job?.isActive == true }

    /**
     * Runs [block] on the shared scope, handing it a publish function that writes progress only
     * while this run is still the live one. No-op if a run is already active.
     */
    fun start(block: suspend (publish: (BulkProgress) -> Unit) -> Unit) {
        val runGeneration = synchronized(lock) {
            if (job?.isActive == true) return
            ++generation
        }
        val publish: (BulkProgress) -> Unit = { state ->
            synchronized(lock) { if (runGeneration == generation) _progress.value = state }
        }
        val started = scope.launch { block(publish) }
        synchronized(lock) {
            // A cancel that landed while this job was being started bumped the generation past
            // ours: this run is already obsolete, so stop it instead of publishing it as current.
            if (runGeneration == generation) job = started else started.cancel()
        }
    }

    fun cancel() {
        val running = synchronized(lock) {
            generation++
            _progress.value = BulkProgress.Idle
            job.also { job = null }
        }
        // Cancelling outside the lock: the running block's own cleanup publishes nothing (its
        // generation is stale now) and must not have to wait on a lock the UI thread holds.
        running?.cancel()
    }

    /** Clear a terminal [BulkProgress.Done] once the UI has shown it. */
    fun acknowledge() = synchronized(lock) {
        if (_progress.value is BulkProgress.Done) _progress.value = BulkProgress.Idle
    }
}

@Suppress("TooManyFunctions") // the app's single library-domain facade
class DefaultLibraryRepository(
    private val db: JellyshelfDatabase,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    sources: LibrarySources,
) : LibraryRepository {
    private val videoDao = db.videoDao()
    private val categoryDao = db.categoryDao()

    // Unpacked once. The four are grouped for the constructor's sake (see [LibrarySources]); every
    // call below still names the source it actually reaches for.
    private val jellyfin = sources.jellyfin
    private val indexSource = sources.index
    private val ytDlp = sources.ytDlp
    private val demo = sources.demo

    /**
     * Serializes every read-modify-write of library rows — sync (manual or from the periodic
     * worker), per-video metadata application and watch-state updates — so concurrent writers
     * can't clobber each other's changes with stale snapshots.
     */
    private val writeMutex = Mutex()

    /**
     * When this process last wrote a video's watch state locally. Sync's Jellyfin snapshot is
     * taken before it acquires [writeMutex], so a local write that lands during the (possibly
     * multi-second) fetch would otherwise be reverted to the server's pre-write state; the
     * merge keeps the local values whenever this stamp postdates the fetch start.
     */
    private val localWatchWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Serializes server playstate writes. Each send re-reads the row's current local state under
     * this lock, so rapid toggles can't commit out of order on the server — the last send always
     * carries the newest local state, whatever order the earlier ones landed in.
     */
    private val playstateMutex = Mutex()

    // Long-running work (the bulk runs, playback reports) happens here so it outlives the screen
    // that started it. Best-effort background work must never crash the process on an
    // unexpected DataStore/DB failure — ioScope logs and moves on.
    private val repoScope = dispatchers.ioScope("LibraryRepository")

    private val fetchRunner = BulkRunner(repoScope)
    override val bulkFetch: StateFlow<BulkProgress> = fetchRunner.progress

    private val removeRunner = BulkRunner(repoScope)
    override val bulkRemove: StateFlow<BulkProgress> = removeRunner.progress

    // Every list flow below ends in `flowOn(dispatchers.default)`: ViewModels collect these through
    // `stateIn(viewModelScope)`, i.e. on Main.immediate, so without it the entity→domain mapping —
    // and, worse, SearchRanking scanning the title, channel, description, tags and categories of a
    // few thousand videos on every keystroke — would run on the main thread and jank the UI.
    // Room already runs the queries themselves on its own executor; this moves the mapping too.
    override fun observeVideos(): Flow<List<Video>> =
        videoDao.observeAll().map { it.map(VideoEntity::toDomain) }.flowOn(dispatchers.default)

    /**
     * Videos filtered to [bucket] (all durations when null) and, for a non-blank [query], narrowed
     * to relevance matches sorted most-relevant first (see [SearchRanking]). A blank query just
     * returns the duration-filtered list by file name.
     */
    override fun searchVideos(query: String, bucket: DurationBucket?): Flow<List<Video>> {
        val source = if (bucket == null) {
            videoDao.observeAll()
        } else {
            videoDao.observeByDurationRange(bucket.minSeconds, bucket.maxSeconds)
        }
        return source.map { SearchRanking.rankVideos(query, it).map(VideoEntity::toDomain) }
            .flowOn(dispatchers.default)
    }

    /** Videos in [categoryId], routing the "Others" virtual filters to live queries. */
    override fun observeVideosByCategory(categoryId: String): Flow<List<Video>> = when (categoryId) {
        VIRTUAL_CATEGORY_UNCATEGORIZED -> videoDao.observeBySource(METADATA_SOURCE_JELLYFIN)
        VIRTUAL_CATEGORY_CONTINUE -> videoDao.observeContinueWatching()
        VIRTUAL_CATEGORY_UNWATCHED -> videoDao.observeUnwatched()
        VIRTUAL_CATEGORY_WATCHED -> videoDao.observeWatched()
        else -> videoDao.observeByCategory(categoryId)
    }.map { it.map(VideoEntity::toDomain) }.flowOn(dispatchers.default)

    override fun observeVideo(youtubeId: String): Flow<Video?> =
        videoDao.observe(youtubeId).map { it?.toDomain() }

    override fun observeCategories(): Flow<List<CategoryWithCount>> =
        categoryDao.observeWithCounts().map { rows -> rows.map { it.toDomain() } }
            .flowOn(dispatchers.default)

    override fun searchCategories(query: String): Flow<List<CategoryWithCount>> =
        categoryDao.searchWithCounts(escapeLikePattern(query))
            .map { ranked -> SearchRanking.rankCategories(query, ranked).map { it.toDomain() } }
            .flowOn(dispatchers.default)

    /**
     * The "Others" tab's virtual filters with live counts: Uncategorized (no yt-dlp/index metadata),
     * Continue watching, Unwatched, Watched. Empty filters are dropped.
     */
    override fun observeOthers(): Flow<List<CategoryWithCount>> = combine(
        videoDao.countBySource(METADATA_SOURCE_JELLYFIN),
        videoDao.countContinueWatching(),
        videoDao.countUnwatched(),
        videoDao.countWatched(),
    ) { uncategorized, continueWatching, unwatched, watched ->
        listOf(
            virtualRow(VIRTUAL_CATEGORY_UNCATEGORIZED, "Uncategorized", uncategorized),
            virtualRow(VIRTUAL_CATEGORY_CONTINUE, "Continue watching", continueWatching),
            virtualRow(VIRTUAL_CATEGORY_UNWATCHED, "Unwatched", unwatched),
            virtualRow(VIRTUAL_CATEGORY_WATCHED, "Watched", watched),
        ).filter { it.videoCount > 0 }
    }

    private fun virtualRow(id: String, name: String, count: Int) = CategoryWithCount(
        category = Category(id = id, name = name, type = CATEGORY_TYPE_OTHERS, createdAt = 0L),
        videoCount = count,
    )

    override fun videoCount(): Flow<Int> = videoDao.count()

    /** Full sync: pull Jellyfin items + metadata index, merge, persist, auto-categorize. */
    override suspend fun sync(): SyncResult {
        val s = settings.snapshot()
        if (s.demoMode) return demoSync()
        if (!s.isConnected) {
            return SyncResult.Error(
                "Not connected. Set server URL, API key and user in Settings.",
                retryable = false,
            )
        }

        val fetchStartedAt = System.currentTimeMillis()
        val items = runCatchingCancellable {
            jellyfin.fetchAllItems(s.serverUrl, s.credential, s.userId, s.libraryId)
        }.getOrElse { e ->
            // A rejected user token can't self-heal, and must not quietly fall back to the
            // advanced API key — that would silently restore the full-server access the user
            // moved away from. Drop the session so the UI asks for a fresh sign-in instead.
            val sessionExpired = isUnauthorized(e) && s.isSignedIn
            if (sessionExpired) settings.clearSession()
            val message = when {
                isCleartextBlocked(e) -> CLEARTEXT_BLOCKED_MESSAGE
                sessionExpired -> SESSION_EXPIRED_MESSAGE
                else -> "Failed to load library: ${e.message}"
            }
            return SyncResult.Error(message, retryable = !isPermanentFailure(e))
        }

        val (index, indexAvailable) = fetchIndex(s)

        val now = System.currentTimeMillis()
        val serverBase = s.serverUrl.trim().removeSuffix("/")
        val mergeContext = SyncMergeContext(serverBase, now, indexAvailable)

        val synced = writeMutex.withLock {
            // Existing rows, to honour newest-wins: a manual in-app yt-dlp fetch is kept over an
            // index entry unless the index entry is genuinely newer. Read inside the lock so no
            // other writer can slip between this snapshot and the upsert below.
            val existingById = videoDao.getAll().associateBy { it.youtubeId }
            val videos = mergedVideos(items, existingById, index, mergeContext, fetchStartedAt)

            val prune = prunePolicy(
                scopeChanged = s.libraryId != s.lastSyncLibraryId,
                storedCount = existingById.size,
                seenCount = videos.size,
            )
            val retained = retainedRows(prune, existingById, videos)
            val (autoCategories, crossRefs) = autoAssignments(videos + retained, now)

            persistSync(prune, now, videos, autoCategories.values, crossRefs)

            settings.setLastSync(now, s.libraryId)
            SyncResult.Success(
                itemCount = items.size,
                matched = videos.size,
                indexed = videos.count { it.metadataSource != METADATA_SOURCE_JELLYFIN },
                categories = autoCategories.size,
                indexDegraded = s.indexUrl.isNotBlank() && !indexAvailable,
            )
        }

        // Strictly *outside* the lock: the sync above is committed and has already succeeded, and
        // every write the auto-fill makes takes [writeMutex] per video for itself. Running it
        // inside would deadlock on the non-reentrant mutex — a whole sync's worth of fetches
        // silently doing nothing until the pass's own budget expired.
        val (autoFilled, autoFillFailed) = autoFillMissingMetadata(demoMode = false)
        return synced.copy(autoFilled = autoFilled, autoFillFailed = autoFillFailed)
    }

    /**
     * What a sync means with no server behind it: the rows already stored *are* the library, so
     * this re-derives from them everything a real sync derives from a listing — auto-categories,
     * the sync stamp — and then runs the same auto-fill pass, which in demo mode extracts from
     * [DemoBackend]. Pressing Sync on a demo install therefore fills in a little more of the
     * missing metadata each time, which is exactly what it does against a real library.
     *
     * Nothing is re-added: videos the bulk removal deleted are gone from the fake server too, and a
     * sync that resurrected them would contradict the run that removed them. Re-entering the demo
     * is what restores the full dataset ([seedDemoLibrary] is idempotent).
     *
     * Never fails. A demo sync has no network to lose, and the failure realism the demo needs is
     * already supplied per video by the auto-fill pass.
     */
    private suspend fun demoSync(): SyncResult {
        demo.sync()
        val now = System.currentTimeMillis()
        val synced = writeMutex.withLock {
            // missedSyncs reset with the stamp: this listing "saw" every stored row, so a row part
            // way through its grace period from an earlier real library must not keep that count.
            val rows = videoDao.getAll().map { it.copy(lastSyncedAt = now, missedSyncs = 0) }
            val (autoCategories, crossRefs) = autoAssignments(rows, now)
            persistSync(Prune.NOTHING, now, rows, autoCategories.values, crossRefs)
            settings.setLastSync(now, "")
            SyncResult.Success(
                itemCount = rows.size,
                matched = rows.size,
                indexed = rows.count { it.metadataSource != METADATA_SOURCE_JELLYFIN },
                categories = autoCategories.size,
            )
        }
        // Outside the lock for the same reason the real sync's pass is — see above.
        val (autoFilled, autoFillFailed) = autoFillMissingMetadata(demoMode = true)
        return synced.copy(autoFilled = autoFilled, autoFillFailed = autoFillFailed)
    }

    /**
     * The metadata index keyed by video id, paired with whether it was actually reachable.
     *
     * A failed index fetch must stay distinguishable from an index with no entries: [mergeVideo]
     * keeps existing index-sourced metadata when the index was unavailable, instead of degrading
     * those rows to bare Jellyfin fields.
     */
    private suspend fun fetchIndex(s: Settings): Pair<Map<String, IndexEntry>, Boolean> {
        if (s.indexUrl.isBlank()) return emptyMap<String, IndexEntry>() to false
        return runCatchingCancellable {
            indexSource.fetchIndex(s.indexUrl).associateBy { it.id } to true
        }.getOrElse { e ->
            // Swallowing this silently made an index URL that 404s indistinguishable from
            // months of healthy syncs: metadata quietly freezes and new videos stay
            // uncategorized. The sync still completes — see the flag above — but it
            // says so, in the log and on the settings status line.
            Timber.w(e, "Metadata index fetch failed; syncing with Jellyfin data only")
            emptyMap<String, IndexEntry>() to false
        }
    }

    /** Every server item that carries a YouTube id, merged with its stored row and index entry. */
    private fun mergedVideos(
        items: List<BaseItemDto>,
        existingById: Map<String, VideoEntity>,
        index: Map<String, IndexEntry>,
        context: SyncMergeContext,
        fetchStartedAt: Long,
    ): List<VideoEntity> = items.mapNotNull { item ->
        val youtubeId = YoutubeId.fromPath(item.path) ?: return@mapNotNull null
        mergeVideo(
            existing = existingById[youtubeId],
            youtubeId = youtubeId,
            item = item,
            meta = index[youtubeId],
            context = context,
            // A local watch-state write that landed after the server snapshot was taken
            // is newer than that snapshot — keep it.
            keepLocalWatchState = (localWatchWrites[youtubeId] ?: 0L) > fetchStartedAt,
        )
    }.distinctBy { it.youtubeId }

    /**
     * Rows this listing missed that [prune] will keep: they stay fully intact, auto-categories
     * included, so a video riding out its grace period doesn't drop out of its
     * channel/year/duration categories only to reappear in them a few hours later.
     */
    private fun retainedRows(
        prune: Prune,
        existingById: Map<String, VideoEntity>,
        videos: List<VideoEntity>,
    ): List<VideoEntity> {
        if (prune == Prune.NOTHING) {
            Timber.w(
                "Sync saw only ${videos.size} of ${existingById.size} stored videos — " +
                    "the server is likely mid-rescan; keeping every stored video this round."
            )
        }
        val seenIds = videos.mapTo(HashSet(videos.size)) { it.youtubeId }
        return when (prune) {
            Prune.IMMEDIATE -> emptyList()
            Prune.NOTHING -> existingById.values.filter { it.youtubeId !in seenIds }
            Prune.GRACE -> existingById.values.filter {
                it.youtubeId !in seenIds && it.missedSyncs + 1 < MAX_MISSED_SYNCS
            }
        }
    }

    /**
     * Auto-categorizes each row along several dimensions: channel, upload year, upload month,
     * duration band and YouTube category. Categories are deduped by id; every membership becomes a
     * cross-ref. Videos with no metadata simply produce no auto-categories (they surface under the
     * "Others" tab's Uncategorized filter instead).
     */
    private fun autoAssignments(
        rows: List<VideoEntity>,
        now: Long,
    ): Pair<Map<String, CategoryEntity>, List<VideoCategoryCrossRef>> {
        val autoCategories = LinkedHashMap<String, CategoryEntity>()
        val crossRefs = mutableListOf<VideoCategoryCrossRef>()
        for (video in rows) {
            for (a in autoAssignmentsOf(video)) {
                autoCategories.getOrPut(a.id) { CategoryEntity(a.id, a.name, a.type, now) }
                crossRefs += VideoCategoryCrossRef(video.youtubeId, a.id)
            }
        }
        return autoCategories to crossRefs
    }

    /** Commits one sync pass: the merged rows, the prune the policy chose, and auto memberships. */
    private suspend fun persistSync(
        prune: Prune,
        now: Long,
        videos: List<VideoEntity>,
        autoCategories: Collection<CategoryEntity>,
        crossRefs: List<VideoCategoryCrossRef>,
    ) {
        db.withTransaction {
            videoDao.upsert(videos)
            // Videos gone from the server leave the library, and auto memberships are rebuilt
            // from scratch so stale assignments (changed channel, date or duration) don't
            // accumulate across syncs. Manual memberships survive except where their video
            // was actually deleted.
            when (prune) {
                Prune.IMMEDIATE -> videoDao.deleteNotSyncedAt(now)
                Prune.GRACE -> {
                    videoDao.markMissedSince(now)
                    videoDao.deleteAfterMissedSyncs(now, MAX_MISSED_SYNCS)
                }
                Prune.NOTHING -> Unit
            }
            categoryDao.clearAutoCrossRefs(keepType = CATEGORY_TYPE_MANUAL)
            categoryDao.upsertAll(autoCategories.toList())
            if (crossRefs.isNotEmpty()) categoryDao.upsertCrossRefs(crossRefs)
            pruneCategoryLeftovers()
        }
    }

    /**
     * What a deleted video leaves behind: its category cross-refs, and any category those were
     * the last members of. Always in the same transaction as the delete that orphaned them —
     * orphan cross-refs are what the Categories screen counts, so a reader in between would see
     * inflated counts and categories that no longer have anything in them.
     *
     * Both queries are full-table passes, which is why callers run this once per batch rather
     * than once per video.
     */
    private suspend fun pruneCategoryLeftovers() {
        categoryDao.pruneOrphanCrossRefs()
        categoryDao.pruneEmptyCategories(CATEGORY_TYPE_MANUAL)
    }

    /**
     * Fills small metadata gaps with the built-in yt-dlp as part of a sync, so a library that is
     * almost fully described doesn't need the user to notice and press "fetch missing".
     *
     * Only runs for a *small* gap ([AUTO_FILL_MAX_MISSING]): a fresh library with hundreds of
     * unmatched videos is a bulk job the user should start deliberately and watch, not something a
     * background worker should spend ten minutes and a battery on.
     *
     * Returns (filled, failed). Never throws: a sync that already committed must not be reported
     * as failed because YouTube refused an extraction.
     */
    private suspend fun autoFillMissingMetadata(demoMode: Boolean): Pair<Int, Int> {
        // A manual bulk fetch is already walking exactly this set — leave it alone rather than
        // racing it for the same rows, and don't even query for them.
        val targets =
            if (fetchRunner.isActive) emptyList() else videoDao.getBySource(METADATA_SOURCE_JELLYFIN)
        if (targets.isEmpty() || targets.size >= AUTO_FILL_MAX_MISSING) return 0 to 0

        var filled = 0
        var failed = 0
        // One budget for the whole pass rather than a shorter per-fetch timeout: this runs inside
        // a WorkManager worker with a ~10 minute execution window, and bounding the pass bounds it
        // directly, whatever any single extraction does. Whatever completed is already persisted —
        // each fetch commits on its own — and the next sync retries the rest.
        withTimeoutOrNull(AUTO_FILL_BUDGET) {
            for (video in targets) {
                when (fetchAndApply(video.youtubeId, demoMode = demoMode)) {
                    is FetchResult.Success -> filled++
                    is FetchResult.Error -> failed++
                }
            }
        } ?: Timber.w("Sync auto-fill hit its $AUTO_FILL_BUDGET budget after $filled/${targets.size}")
        return filled to failed
    }

    private data class AutoAssignment(val id: String, val name: String, val type: String)

    /** The auto-categories a single [video] belongs to, along every dimension. */
    private fun autoAssignmentsOf(video: VideoEntity): List<AutoAssignment> = buildList {
        video.channel?.takeIf { it.isNotBlank() }?.let { channel ->
            val id = "channel:" + (video.channelId?.takeIf { it.isNotBlank() } ?: channel)
            add(AutoAssignment(id, channel, CATEGORY_TYPE_AUTO_CHANNEL))
        }
        yearOf(video.uploadDate)?.let { add(AutoAssignment("year:$it", it, CATEGORY_TYPE_AUTO_YEAR)) }
        yearMonthOf(video.uploadDate)?.let { add(AutoAssignment("month:$it", it, CATEGORY_TYPE_AUTO_MONTH)) }
        DurationBucket.of(video.durationSeconds)?.let {
            add(AutoAssignment("duration:${it.id}", it.label, CATEGORY_TYPE_AUTO_DURATION))
        }
        for (raw in video.youtubeCategories) {
            val name = raw.trim()
            if (name.isNotBlank()) add(AutoAssignment("ytcat:$name", name, CATEGORY_TYPE_AUTO_YT_CATEGORY))
        }
    }

    /**
     * Fetch metadata for one video in-app with yt-dlp, overwrite its record (source = YTDLP,
     * stamped now) and re-derive its auto-categories. Same fields/converters as an index match.
     */
    override suspend fun fetchMetadata(youtubeId: String): FetchResult {
        val existing = videoDao.get(youtubeId) ?: return FetchResult.Error(VIDEO_NOT_FOUND_ERROR)
        return fetchAndApply(
            youtubeId,
            fallbackTitle = existing.title,
            demoMode = settings.snapshot().demoMode,
        )
    }

    /**
     * One yt-dlp fetch and its write, shared by every path that fetches metadata: the single-video
     * action, the manual bulk run and the sync auto-fill.
     *
     * [demoMode] swaps the real extraction for [DemoBackend]'s — resolved once per run by the
     * caller rather than read here, so a bulk pass over hundreds of videos doesn't re-read
     * DataStore for each of them. Everything downstream is identical: the demo backend throws the
     * same way and returns the same [IndexEntry], so the write, the failure record and the
     * auto-category refresh are literally the same code.
     *
     * A failure is recorded on the row ([recordFetchFailure]) as well as returned, so a video that
     * keeps failing can explain itself on the detail screen instead of sitting in Uncategorized
     * with no reason given.
     */
    private suspend fun fetchAndApply(
        youtubeId: String,
        fallbackTitle: String? = null,
        demoMode: Boolean,
    ): FetchResult {
        val entry = runCatchingCancellable {
            if (demoMode) demo.fetchMetadata(youtubeId) else ytDlp.fetch(youtubeId)
        }.getOrElse { e ->
            val message = e.message ?: FETCH_FAILED_ERROR
            recordFetchFailure(youtubeId, message)
            return FetchResult.Error(message)
        }
        // Applying can still fail if the video was deleted underneath us; that isn't a fetch
        // failure, so it leaves no error on a row that no longer exists.
        return if (applyFetched(youtubeId, entry)) {
            FetchResult.Success(entry.title ?: fallbackTitle.orEmpty())
        } else {
            FetchResult.Error(VIDEO_NOT_FOUND_ERROR)
        }
    }

    /** Stamp [message] on [youtubeId]'s row as its latest yt-dlp failure. No-op if it's gone. */
    private suspend fun recordFetchFailure(youtubeId: String, message: String) =
        writeMutex.withLock {
            val existing = videoDao.get(youtubeId) ?: return@withLock
            videoDao.upsert(
                existing.copy(
                    lastFetchError = message,
                    lastFetchErrorAt = System.currentTimeMillis(),
                ),
            )
        }

    /**
     * Overwrite [youtubeId]'s row with yt-dlp [entry] metadata and refresh its auto-categories.
     * Re-reads the row under [writeMutex] so a sync or watch-state write that landed since the
     * caller's snapshot is never reverted. Returns false when the video no longer exists.
     */
    private suspend fun applyFetched(youtubeId: String, entry: IndexEntry): Boolean =
        writeMutex.withLock {
            val existing = videoDao.get(youtubeId) ?: return@withLock false
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                title = entry.title ?: existing.title,
                channel = entry.channel ?: existing.channel,
                channelId = entry.channelId ?: existing.channelId,
                durationSeconds = entry.duration ?: existing.durationSeconds,
                uploadDate = entry.uploadDate ?: existing.uploadDate,
                description = entry.description ?: existing.description,
                tags = entry.tags ?: existing.tags,
                youtubeCategories = entry.categories ?: existing.youtubeCategories,
                // Strip a legacy embedded api key so it can't persist past this write.
                thumbnailUrl = entry.thumbnail ?: stripCredentials(existing.thumbnailUrl),
                metadataSource = METADATA_SOURCE_YTDLP,
                metadataUpdatedAt = now,
                lastSyncedAt = now,
                // This fetch worked — whatever the last one failed with is history.
                lastFetchError = null,
                lastFetchErrorAt = 0L,
            )

            // Re-derive this video's auto memberships: drop the old ones, add the new, prune orphans.
            val assignments = autoAssignmentsOf(updated)
            db.withTransaction {
                videoDao.upsert(updated)
                categoryDao.removeAutoCrossRefsForVideo(updated.youtubeId, keepType = CATEGORY_TYPE_MANUAL)
                categoryDao.upsertAll(assignments.map { CategoryEntity(it.id, it.name, it.type, now) })
                if (assignments.isNotEmpty()) {
                    categoryDao.upsertCrossRefs(assignments.map { VideoCategoryCrossRef(updated.youtubeId, it.id) })
                }
                categoryDao.pruneEmptyCategories(CATEGORY_TYPE_MANUAL)
            }
            true
        }

    /**
     * Fetch metadata for every uncategorized (Jellyfin-only) video, one at a time, publishing
     * progress via [bulkFetch]. No-op if already running.
     */
    override fun startFetchMissing() = fetchRunner.start { publish ->
        val demoMode = settings.snapshot().demoMode
        val targets = videoDao.getBySource(METADATA_SOURCE_JELLYFIN)
        if (targets.isEmpty()) {
            publish(BulkProgress.Done(0, 0))
            return@start
        }
        var failed = 0
        publish(BulkProgress.Running(0, targets.size, 0))
        targets.forEachIndexed { i, video ->
            // A failure applying the result counts as failed too, so an unexpected exception
            // can't kill the process or strand the Running state. A per-video timeout inside
            // YtDlpMetadataSource keeps one hung extraction from stalling the whole run.
            val ok = runCatchingCancellable {
                fetchAndApply(video.youtubeId, demoMode = demoMode) is FetchResult.Success
            }.getOrDefault(false)
            if (!ok) failed++
            publish(BulkProgress.Running(i + 1, targets.size, failed))
        }
        publish(BulkProgress.Done(targets.size, failed))
    }

    override fun cancelFetchMissing() = fetchRunner.cancel()

    override fun acknowledgeBulkFetch() = fetchRunner.acknowledge()

    /**
     * Deletes every watched video from the Jellyfin server — media file included — one at a time,
     * publishing progress via [bulkRemove]. No-op if already running.
     *
     * A video the server refuses to delete (no rights, already gone, network down) is counted as
     * failed and the run moves on, so one bad item never strands the rest; the summary reports how
     * many were left behind. Retrying is just a matter of pressing the button again — a successful
     * delete has already left the watched set.
     *
     * In demo mode the deletes go to [DemoBackend] instead, and are just as real from the library's
     * point of view: a confirmed one drops the row. Only re-entering the demo brings it back.
     */
    override fun startRemoveWatched() = removeRunner.start { publish ->
        val s = settings.snapshot()
        val targets = videoDao.getWatched()
        when {
            targets.isEmpty() -> {
                publish(BulkProgress.Done(0, 0))
                return@start
            }
            // Nothing can be deleted without a server to delete it on. Reported as all-failed
            // rather than as a clean run, which would read as "the videos are gone" when they
            // are all still there. A demo has a stand-in server, so it doesn't come through here.
            !s.isConnected && !s.demoMode -> {
                publish(BulkProgress.Done(targets.size, targets.size))
                return@start
            }
        }

        var failed = 0
        publish(BulkProgress.Running(0, targets.size, 0))
        try {
            targets.forEachIndexed { i, video ->
                val removed = runCatchingCancellable { removeFromServer(s, video) }
                    .getOrElse { e ->
                        Timber.w(e, "Remove watched: server delete failed for ${video.youtubeId}")
                        false
                    }
                if (!removed) failed++
                publish(BulkProgress.Running(i + 1, targets.size, failed))
            }
        } finally {
            // In a `finally` because a cancelled run has still deleted rows, and what they left
            // behind has to go with them.
            withContext(NonCancellable) {
                writeMutex.withLock { db.withTransaction { pruneCategoryLeftovers() } }
            }
        }
        publish(BulkProgress.Done(targets.size, failed))
    }

    /**
     * Deletes [video] on the server, then drops its local row. Server first, and only on success:
     * deleting locally on its own would achieve nothing, since the next sync would find the video
     * still on the server and put the row straight back.
     *
     * Returns false for a video that was never matched to a Jellyfin item — there is nothing to
     * delete, and dropping it locally would hide a video that still exists.
     */
    private suspend fun removeFromServer(s: Settings, video: VideoEntity): Boolean {
        val itemId = video.jellyfinItemId ?: return false
        // Demo rows all carry the sentinel item id, so the guard above passes and the delete is
        // simulated rather than sent — a demo install has no server, and the sentinel must never
        // reach one anyway.
        if (s.demoMode) demo.deleteItem() else jellyfin.deleteItem(s.serverUrl, s.credential, itemId)
        // NonCancellable: a cancel landing between the server delete and the local one would leave
        // a row for a video that no longer exists, which only the next sync would clear.
        withContext(NonCancellable) {
            writeMutex.withLock { videoDao.delete(video.youtubeId) }
        }
        return true
    }

    override fun cancelRemoveWatched() = removeRunner.cancel()

    override fun acknowledgeBulkRemove() = removeRunner.acknowledge()

    /**
     * Fills the library from the bundled demo dataset, then marks the install as a demo.
     *
     * Deliberately built out of the sync path's own parts — [autoAssignments] and [persistSync] —
     * rather than a parallel writer: the Categories screen after a seed must be the same screen it
     * is after a real sync, and the only way to guarantee that is to derive it with the same code.
     * [Prune.NOTHING] because there is no server listing to compare against; the upserts are keyed
     * by video id, which is what makes re-seeding idempotent.
     *
     * [setLastSync][SettingsRepository.setLastSync] with an empty scope is what moves the app off
     * the Settings screen on the next launch (`MainViewModel` starts on Library whenever the
     * library has ever been populated), and it leaves the connection fields untouched — a demo
     * install has none.
     */
    override suspend fun seedDemoLibrary() {
        val entries = indexSource.demoEntries()
        val now = System.currentTimeMillis()
        val videos = entries.mapIndexed { index, entry -> demoVideo(entry, index, now) }
        // NonCancellable for the same reason as clearLocalData: the rows and the flag that says
        // what they are must land together, or the app describes a library it doesn't have.
        withContext(NonCancellable) {
            writeMutex.withLock {
                val (autoCategories, crossRefs) = autoAssignments(videos, now)
                persistSync(Prune.NOTHING, now, videos, autoCategories.values, crossRefs)
                settings.setLastSync(now, "")
                settings.setDemoMode(true)
            }
        }
    }

    /**
     * One demo entry → one row, mapped exactly as [mergeVideo]'s index branch maps a real one, so
     * a demo library exercises the same fields the app reads everywhere else.
     *
     * Entries carrying no index metadata at all (a bare title, as an unmatched file on a real
     * server produces) are stored as Jellyfin-sourced rather than index-sourced: that is what they
     * would be after a real sync, and it gives the "Uncategorized" filter genuine members instead
     * of an empty row on the Others tab.
     */
    private fun demoVideo(entry: IndexEntry, index: Int, now: Long): VideoEntity {
        val title = entry.title ?: entry.id
        val indexed = entry.hasIndexMetadata
        val durationSeconds = entry.duration ?: 0L
        val watch = demoWatchState(index, durationSeconds)
        return VideoEntity(
            youtubeId = entry.id,
            jellyfinItemId = DEMO_ITEM_ID,
            // Real rows take the file name from the server path; demo rows have no server, and the
            // title is what that file would have been called. It drives the universal browse order.
            fileName = "$title.mp4",
            title = title,
            channel = entry.channel,
            channelId = entry.channelId,
            durationSeconds = durationSeconds,
            uploadDate = entry.uploadDate,
            description = entry.description,
            chapters = entry.chapters.toChapters(),
            tags = entry.tags.orEmpty(),
            youtubeCategories = entry.categories.orEmpty(),
            thumbnailUrl = entry.thumbnail,
            played = watch.played,
            playbackPositionTicks = watch.positionTicks,
            playCount = watch.playCount,
            lastSyncedAt = now,
            metadataSource = if (indexed) METADATA_SOURCE_INDEX else METADATA_SOURCE_JELLYFIN,
            metadataUpdatedAt = if (indexed) (entry.updatedAtMillis ?: now) else 0L,
        )
    }

    /**
     * Watch state by position in the dataset rather than authored in the JSON: the mix is then
     * guaranteed — Watched, Continue watching and Unwatched all have members however the content
     * is later edited — and the asset stays pure content.
     *
     * Played wins where the two cycles coincide, and a video of unknown length can't be
     * mid-watched (the progress bar divides by its duration).
     */
    private fun demoWatchState(index: Int, durationSeconds: Long): DemoWatchState = when {
        index % DEMO_PLAYED_EVERY == DEMO_PLAYED_EVERY - 1 ->
            DemoWatchState(played = true, positionTicks = 0L, playCount = 1)
        index % DEMO_IN_PROGRESS_EVERY == DEMO_IN_PROGRESS_EVERY - 1 && durationSeconds > 0 ->
            DemoWatchState(
                played = false,
                positionTicks = millisToTicks(
                    durationSeconds * MILLIS_PER_SECOND * DEMO_RESUME_PERCENT / PERCENT,
                ),
                playCount = 0,
            )
        else -> DemoWatchState(played = false, positionTicks = 0L, playCount = 0)
    }

    /**
     * Wipe all locally cached library data (videos, categories, their links) and
     * reset the last-sync marker. Connection settings — server URL, API key, user,
     * folder scope — are left untouched, so a subsequent sync rebuilds from scratch.
     *
     * This is also the way out of demo mode, which is why the flag goes with the rows: it
     * describes what the library holds, and after this the library holds nothing.
     */
    override suspend fun clearLocalData() {
        // NonCancellable: a cancellation landing between the wipe and the marker reset would
        // leave an empty library still claiming it synced recently, and the next sync would
        // treat every video as newly deleted.
        withContext(NonCancellable) {
            writeMutex.withLock {
                db.withTransaction {
                    categoryDao.clearCrossRefs()
                    categoryDao.clearCategories()
                    videoDao.clear()
                }
                settings.setLastSync(0L, "")
                settings.setDemoMode(false)
            }
        }
    }

    /**
     * Drops one video from the local library, with its category memberships. For a video the sync
     * reports as gone from the server (see [Video.missingFromServer]) whose grace period the user
     * doesn't want to wait out — nothing is deleted on the server, and a video that turns out to
     * still be there comes back on the next sync.
     */
    override suspend fun removeVideo(youtubeId: String) {
        // NonCancellable, like clearLocalData: this is invoked from a screen that pops itself
        // the moment the row disappears, and a half-applied delete would leave orphan cross-refs.
        withContext(NonCancellable) {
            writeMutex.withLock {
                db.withTransaction {
                    videoDao.delete(youtubeId)
                    pruneCategoryLeftovers()
                }
            }
        }
    }

    /**
     * Toggles [youtubeId]'s watch state locally and mirrors it to Jellyfin. Returns false when
     * the server write failed — the local state stays, but the next sync may revert it to the
     * server's value, so callers should tell the user.
     */
    override suspend fun setPlayed(youtubeId: String, played: Boolean): Boolean {
        writeMutex.withLock {
            val v = videoDao.get(youtubeId) ?: return false
            videoDao.updateWatchState(youtubeId, played, if (played) v.playbackPositionTicks else 0L)
            localWatchWrites[youtubeId] = System.currentTimeMillis()
        }
        val s = settings.snapshot()
        return runCatchingCancellable {
            playstateMutex.withLock {
                // Re-read at send time: if another toggle landed while this one waited for the
                // lock, send the newer state — the server then converges on the latest local
                // value regardless of how the calls interleaved.
                val latest = videoDao.get(youtubeId) ?: return false
                val itemId = latest.jellyfinItemId
                if (s.isConnected && itemId != null) {
                    jellyfin.setPlayed(s.serverUrl, s.credential, s.userId, itemId, latest.played)
                }
            }
            true
        }.getOrElse { e ->
            Timber.tag(PLAYBACK_TAG).w(e, "setPlayed: server write failed for youtubeId=$youtubeId")
            false
        }
    }

    /**
     * Record where an external player stopped: persist the resume position locally and mirror the
     * result to Jellyfin.
     *
     * - Finished (played to the end, or stopped within a few seconds of it): mark played through
     *   the dedicated /PlayedItems endpoint — the same one the manual "watched" toggle uses. That
     *   is what increments PlayCount, stamps LastPlayedDate and lands the item in the server's
     *   watch history. Writing UserData with Played=true does *not* reliably register a play.
     * - Stopped partway: write the resume position to the user's item data, which is what surfaces
     *   the item in "Continue Watching" (the /Sessions endpoints only commit for a live,
     *   progress-tracked session, which an external-player handoff can't sustain).
     *
     * Best-effort — network failures are swallowed so local state still updates.
     */
    override fun reportPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean) {
        // Fire-and-forget on the repository's own scope: the screen that launched the external
        // player may be gone (back press, rotation) before the local write and the network
        // report finish, and losing the resume position is not acceptable.
        repoScope.launch { onPlaybackStopped(youtubeId, positionMs, completed) }
    }

    override fun savePlaybackPosition(youtubeId: String, positionMs: Long) {
        // Fire-and-forget like reportPlaybackStopped: the playback service calls this every few
        // seconds and must never wait on Room. The DAO query itself skips played rows.
        val positionTicks = millisToTicks(positionMs)
        repoScope.launch {
            writeMutex.withLock {
                // Ticks only start being recorded once the video is genuinely under way
                // ([isWorthResuming]). Gating the stop report alone would not be enough — this
                // runs every few seconds regardless, so the trivial position would already be on
                // disk, and both the resume seed and the next sync would treat it as real.
                val v = videoDao.get(youtubeId) ?: return@withLock
                if (!isWorthResuming(positionTicks, v.durationSeconds)) return@withLock
                videoDao.updatePlaybackPosition(youtubeId, positionTicks)
                // Stamped like onPlaybackStopped's write: a sync whose server snapshot predates
                // this save must keep the local position, not revert it.
                localWatchWrites[youtubeId] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun onPlaybackStopped(youtubeId: String, positionMs: Long, completed: Boolean) {
        val positionTicks = millisToTicks(positionMs)
        Timber.tag(PLAYBACK_TAG).d(
            "onPlaybackStopped: youtubeId=$youtubeId positionMs=$positionMs " +
                "positionTicks=$positionTicks completed=$completed",
        )
        var finished = completed
        val video = writeMutex.withLock {
            val v = videoDao.get(youtubeId) ?: run {
                Timber.tag(PLAYBACK_TAG).w(
                    "onPlaybackStopped: no local row for youtubeId=$youtubeId; nothing to report",
                )
                return
            }
            finished = completed ||
                v.durationSeconds > 0 &&
                ticksToSeconds(positionTicks) >= v.durationSeconds - COMPLETION_THRESHOLD_SECONDS
            // Barely into it and not finished — stepping past this video rather than watching it.
            // Nothing is written at all, locally or to the server: the position it already holds
            // is a better answer than the one this stop would replace it with.
            if (!finished && !isWorthResuming(positionTicks, v.durationSeconds)) {
                Timber.tag(PLAYBACK_TAG).d(
                    "onPlaybackStopped: only ${ticksToSeconds(positionTicks)}s into " +
                        "${v.durationSeconds}s; leaving the stored position alone",
                )
                return
            }
            Timber.tag(PLAYBACK_TAG).d(
                "onPlaybackStopped: durationSeconds=${v.durationSeconds} finished=$finished -> " +
                    "local write played=$finished position=${if (finished) 0L else positionTicks}",
            )
            videoDao.updateWatchState(youtubeId, finished, if (finished) 0L else positionTicks)
            localWatchWrites[youtubeId] = System.currentTimeMillis()
            v
        }

        val s = settings.snapshot()
        val itemId = video.jellyfinItemId
        if (!s.isConnected || itemId == null) {
            Timber.tag(PLAYBACK_TAG).d(
                "onPlaybackStopped: skipping server report (connected=${s.isConnected} " +
                    "itemId=$itemId)",
            )
            return
        }
        // Best-effort: the local resume position is already saved, so a failed server
        // write is swallowed.
        reportPlaybackToServer(youtubeId, itemId, s)
    }

    private suspend fun reportPlaybackToServer(youtubeId: String, itemId: String, s: Settings) {
        runCatchingCancellable {
            playstateMutex.withLock {
                // Re-read at send time (see setPlayed): a toggle that landed while this
                // report waited for the lock must not be overwritten with older state.
                val latest = videoDao.get(youtubeId) ?: return
                if (latest.played) {
                    // Finished — record the play in Jellyfin's watch history via the endpoint
                    // that actually marks items played (PlayCount++, LastPlayedDate, resume cleared).
                    Timber.tag(PLAYBACK_TAG).d("onPlaybackStopped: marking played on Jellyfin itemId=$itemId")
                    jellyfin.setPlayed(s.serverUrl, s.credential, s.userId, itemId, played = true)
                } else {
                    // Stopped partway — persist the resume position for "Continue Watching".
                    Timber.tag(PLAYBACK_TAG).d(
                        "onPlaybackStopped: writing resume position to Jellyfin itemId=$itemId " +
                            "positionTicks=${latest.playbackPositionTicks}",
                    )
                    jellyfin.updatePlaybackState(
                        serverUrl = s.serverUrl,
                        credential = s.credential,
                        userId = s.userId,
                        itemId = itemId,
                        positionTicks = latest.playbackPositionTicks,
                        played = false,
                        lastPlayedDate = Instant.now().toString(),
                    )
                }
            }
            Timber.tag(PLAYBACK_TAG).d("onPlaybackStopped: server report succeeded for itemId=$itemId")
        }.onFailure { e ->
            Timber.tag(PLAYBACK_TAG).w(e, "onPlaybackStopped: server report failed for itemId=$itemId")
        }
    }

    /**
     * Creates a Jellyfin playlist named [name] from every video in [categoryId] that has a
     * Jellyfin item, ordered by file name. Works for stored categories and "Others" filters alike.
     */
    override suspend fun createPlaylistFromCategory(categoryId: String, name: String): PlaylistResult {
        val s = settings.snapshot()
        if (!s.isConnected && !s.demoMode) {
            return PlaylistResult.Error("Not connected. Configure Jellyfin in Settings.")
        }

        val playlistName = name.trim()
        if (playlistName.isBlank()) return PlaylistResult.Error("Playlist name can't be empty.")

        val itemIds = videosForCategory(categoryId).mapNotNull { it.jellyfinItemId }
        if (itemIds.isEmpty()) return PlaylistResult.Error("No playable videos in this category.")

        return runCatchingCancellable {
            // The demo's playlist is write-only, exactly like the real one from this app's side:
            // nothing here ever reads a playlist back, so there is nothing to keep.
            if (s.demoMode) {
                demo.createPlaylist()
            } else {
                jellyfin.createPlaylist(s.serverUrl, s.credential, s.userId, playlistName, itemIds)
            }
            PlaylistResult.Success(playlistName, itemIds.size)
        }.getOrElse { e ->
            PlaylistResult.Error("Failed to create playlist: ${e.message}")
        }
    }

    /** Videos in [categoryId], ordered by file name — routes the "Others" virtual filters. */
    private suspend fun videosForCategory(categoryId: String): List<VideoEntity> = when (categoryId) {
        VIRTUAL_CATEGORY_UNCATEGORIZED -> videoDao.getBySource(METADATA_SOURCE_JELLYFIN)
        VIRTUAL_CATEGORY_CONTINUE -> videoDao.getContinueWatching()
        VIRTUAL_CATEGORY_UNWATCHED -> videoDao.getUnwatched()
        VIRTUAL_CATEGORY_WATCHED -> videoDao.getWatched()
        else -> videoDao.getByCategory(categoryId)
    }
}
