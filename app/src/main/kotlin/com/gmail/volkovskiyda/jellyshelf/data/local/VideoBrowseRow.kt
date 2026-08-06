package com.gmail.volkovskiyda.jellyshelf.data.local

/**
 * The columns a library list row actually needs — the return type of the projected browse queries
 * in [VideoDao]. Not an entity and not a view: Room maps a query result into any class whose
 * property names match the returned columns.
 *
 * It exists because a list emission of a large library spends most of its time reading two columns
 * no row renders. `description` is the widest column in the table and `chapters` costs a JSON parse
 * per row, and Room re-runs every mounted browse `Flow` on any write to `videos` — including the
 * playback-position save every ten seconds while an unwatched video plays.
 *
 * A distinct type rather than a partially-selected [VideoEntity] is the point: a `VideoEntity` with
 * no description would still compile its way into
 * [com.gmail.volkovskiyda.jellyshelf.data.repository.SearchRanking], which scores that field, and
 * silently narrow what search matches. This way that is a compile error.
 *
 * Why these twelve, and no more:
 * - [youtubeId] — the `items(...)` key in both lists, and what a click hands back.
 * - [jellyfinItemId] — whether the row has anything playable behind it. `VideoRow` sends the
 *   thumbnail to the player when it does and to the detail screen when it does not, and announces
 *   a different label either way, so a null here silently costs every row its play affordance.
 * - [fileName] — the sort order of every browse query, and the scroll anchor both lists restore to.
 * - [title], [channel], [durationSeconds], [uploadDate], [played] — rendered by `VideoRow`.
 * - [thumbnailUrl] — resolved once per screen by `rememberVideoThumbnailResolver`, one level above
 *   `VideoRow`, which takes an already-resolved model.
 * - [playbackPositionTicks] — the row's progress bar.
 * - [missedSyncs] — feeds [com.gmail.volkovskiyda.jellyshelf.domain.model.Video.missingFromServer],
 *   which the row renders as the "missing from server" notice.
 * - [metadataSource] — the one column no list consumer reads. It is here so the mapper can fill
 *   `Video.metadataSource`, which has no default, with the truth rather than a placeholder; it is
 *   `TEXT` with no converter, so it costs essentially nothing.
 *
 * Deliberately absent: `tags` and `youtubeCategories`, which no row renders and which would drag
 * their two JSON parses back in.
 */
data class VideoBrowseRow(
    val youtubeId: String,
    val jellyfinItemId: String?,
    val fileName: String,
    val title: String,
    val channel: String?,
    val durationSeconds: Long,
    val uploadDate: String?,
    val thumbnailUrl: String?,
    val played: Boolean,
    val playbackPositionTicks: Long,
    val missedSyncs: Int,
    val metadataSource: String,
)
