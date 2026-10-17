package com.gmail.volkovskiyda.jellyshelf.domain.model

// Category dimension types. These string values are persisted in the database, so they must never
// change.
const val CATEGORY_TYPE_AUTO_CHANNEL = "AUTO_CHANNEL"
const val CATEGORY_TYPE_AUTO_YEAR = "AUTO_YEAR"
const val CATEGORY_TYPE_AUTO_MONTH = "AUTO_MONTH"
const val CATEGORY_TYPE_AUTO_DURATION = "AUTO_DURATION"
const val CATEGORY_TYPE_AUTO_YT_CATEGORY = "AUTO_YT_CATEGORY"
const val CATEGORY_TYPE_MANUAL = "MANUAL"

/** Synthetic type for the "Others" tab's virtual filters (see [VIRTUAL_CATEGORY_UNCATEGORIZED] etc.). */
const val CATEGORY_TYPE_OTHERS = "OTHERS"

// Virtual category ids for the "Others" tab. These are not stored rows — the repository routes
// them to live queries over the videos table, so they always reflect current state.
const val VIRTUAL_CATEGORY_UNCATEGORIZED = "virtual:uncategorized"
const val VIRTUAL_CATEGORY_WATCHED = "virtual:watched"
const val VIRTUAL_CATEGORY_UNWATCHED = "virtual:unwatched"
const val VIRTUAL_CATEGORY_CONTINUE = "virtual:continue"

/**
 * The most recently played videos, newest first — the one filter whose members are ordered by
 * recency rather than file name. Any play counts, finished or not: a video watched for a minute
 * yesterday sits above one finished last week, and plays from other Jellyfin clients arrive with
 * the next sync via the server's `LastPlayedDate`. Capped at [LAST_PLAYED_LIMIT] so it stays a
 * "recent history" feed rather than a recency-sorted duplicate of Watched.
 */
const val VIRTUAL_CATEGORY_LAST_PLAYED = "virtual:last_played"

/** How many videos the [VIRTUAL_CATEGORY_LAST_PLAYED] filter shows. */
const val LAST_PLAYED_LIMIT = 20

/**
 * Videos the server has stopped listing — see [Video.missingFromServer]. Almost always videos
 * deleted on the server that the app hasn't been told about: sync counts missed appearances
 * rather than deleting on the first one, so they sit in the library looking real until the grace
 * period runs out. This filter is where they can be seen together and dropped in one go.
 */
const val VIRTUAL_CATEGORY_MISSING = "virtual:missing"

/**
 * Every skipped video — the ones the Now playing panel's ✕ marked, which every queue passes over
 * until they are unskipped. This filter is where they can be seen together and unskipped, one at a
 * time or as a selection. A row tap opens the video's details rather than playing it: playing from
 * here would queue a list of nothing but skipped videos.
 */
const val VIRTUAL_CATEGORY_SKIPPED = "virtual:skipped"

/**
 * The playback session's queue in play order, skipped videos included — the Others tab's view of
 * what the player's Now playing panel shows. The one filter not backed by a query over the
 * library: its members are whatever the session holds. Hidden while nothing is queued, like the
 * other filters while they are empty. A row tap jumps playback to that video rather than queuing
 * this list again, which is what a row tap on every other filter does.
 */
const val VIRTUAL_CATEGORY_NOW_PLAYING = "virtual:now_playing"

/** A category as the UI sees it. The data layer maps its Room row (`CategoryEntity`) onto this. */
data class Category(
    val id: String,
    val name: String,
    val type: String,
    val createdAt: Long,
)

/** A [Category] plus its member count, for the categories list. */
data class CategoryWithCount(
    val category: Category,
    val videoCount: Int,
)
