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

/**
 * Progress of a bulk run over the library — "fetch all missing metadata" and "remove all watched"
 * both report through it. Observable so it survives navigating away from the screen and back.
 *
 * [Running.failed] and [Done.failed] count videos the run could not process; it keeps going past
 * them, so a single unreachable video never strands the rest.
 */
sealed interface BulkProgress {
    data object Idle : BulkProgress
    data class Running(val done: Int, val total: Int, val failed: Int) : BulkProgress
    data class Done(val total: Int, val failed: Int) : BulkProgress
}

/**
 * What a multi-selection of videos is being acted on with. One enum rather than four repository
 * entry points, because a selection run is one runner: only one can be in flight at a time, and
 * every label its progress header and confirmation dialog need rides on the action.
 *
 * [REMOVE] is the destructive one, and the only one whose meaning depends on the video it lands
 * on: one the server still lists is deleted there, media file included, while one the server has
 * already stopped listing — or never matched to a Jellyfin item at all — is only dropped locally,
 * because there is nothing left on the server to delete.
 */
enum class SelectionAction {
    MARK_WATCHED,
    MARK_UNWATCHED,
    UPDATE_METADATA,
    REMOVE,
}

/**
 * A selection run and the progress it has reached. One flow rather than two, so a screen can never
 * pair a progress update with the wrong action — the header labels itself off [action], and the
 * "did the removal finish?" check the selection UI makes reads both halves of the same value.
 */
data class SelectionRun(val action: SelectionAction, val progress: BulkProgress)
