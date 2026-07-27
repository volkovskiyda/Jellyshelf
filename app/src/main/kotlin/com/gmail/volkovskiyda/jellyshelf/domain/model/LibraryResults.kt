package com.gmail.volkovskiyda.jellyshelf.domain.model

/** Outcome of a full library sync. */
sealed interface SyncResult {
    /**
     * @param itemCount total Jellyfin items scanned.
     * @param matched videos with a parseable YouTube id (the library total).
     * @param indexed videos that also had a jellyshelf-index.json / yt-dlp metadata match.
     * @param categories distinct auto-categories produced.
     * @param indexDegraded an index URL is configured but this sync could not fetch it. The sync
     *   itself succeeded — existing metadata was kept rather than downgraded — but new videos
     *   stay uncategorized until the index is reachable again, which otherwise looks identical
     *   to months of healthy syncs.
     * @param autoFilled videos whose metadata this sync fetched with the built-in yt-dlp, after
     *   the merge left only a handful without any. Zero when the pass didn't run.
     * @param autoFillFailed videos that auto-fill pass tried and could not fetch. Reported rather
     *   than failing the sync: the sync itself already succeeded.
     */
    data class Success(
        val itemCount: Int,
        val matched: Int,
        val indexed: Int,
        val categories: Int,
        val indexDegraded: Boolean = false,
        val autoFilled: Int = 0,
        val autoFillFailed: Int = 0,
    ) : SyncResult

    /**
     * [retryable] separates transient failures (network, 5xx) from configuration ones (revoked
     * key, missing scope) that can't self-heal — the periodic worker must not retry the latter.
     */
    data class Error(val message: String, val retryable: Boolean = true) : SyncResult
}

/** Outcome of creating a Jellyfin playlist from a category. */
sealed interface PlaylistResult {
    data class Success(val name: String, val count: Int) : PlaylistResult
    data class Error(val message: String) : PlaylistResult
}

/** Outcome of an in-app yt-dlp metadata fetch for a single video. */
sealed interface FetchResult {
    data class Success(val title: String) : FetchResult
    data class Error(val message: String) : FetchResult
}

/** Progress of the bulk "fetch all missing" run, observable so it survives navigation. */
sealed interface BulkFetch {
    data object Idle : BulkFetch
    data class Running(val done: Int, val total: Int, val failed: Int) : BulkFetch
    data class Done(val total: Int, val failed: Int) : BulkFetch
}
