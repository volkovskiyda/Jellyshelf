@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.util

import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import androidx.media3.extractor.metadata.Chapter as MediaChapter

/**
 * Chapters read out of the media file itself, as opposed to the ones Jellyfin sends us.
 *
 * media3 1.11.0 learned to extract chapters from MP4 (both the Nero `chpl` atom and QuickTime
 * chapter tracks) and from Matroska, and surfaces them as
 * [Chapter][androidx.media3.extractor.metadata.Chapter] entries on the track metadata. For anything
 * this app streams from a server that is redundant — the sync already carries structured chapters,
 * and description timecodes beat both (see `PlayerViewModel.chapters`, whose priority is a locked
 * decision). It earns its keep only for local media, which today means the bundled demo clip and a
 * `file:`/`asset:` URI.
 *
 * **The demo clip has no chapters**, verified 2026-08-27 — `sample.mp4` carries neither a `chpl`
 * atom nor a `chap` track reference. So this reads as nothing on every video the shipped app
 * currently plays, and exists for the media it *might* play rather than for the media it does. The
 * instrumented test drives a purpose-built fixture instead, which is the only place the path is
 * exercised at all; without it this would be untested wiring.
 */
internal fun Tracks.embeddedChapters(): List<Chapter> = groups
    .asSequence()
    .flatMap { group -> (0 until group.length).asSequence().map(group::getTrackFormat) }
    .mapNotNull { it.metadata }
    .flatMap { metadata -> (0 until metadata.length()).asSequence().map(metadata::get) }
    .filterIsInstance<MediaChapter>()
    // A hidden chapter is one the container marks as not for display — usually an intro or a
    // sponsor marker meant for tooling rather than for a chapter list.
    .filterNot { it.isHidden }
    // A start the extractor could not read arrives as C.TIME_UNSET, not as an absent entry —
    // both MP4 `chpl` (BoxParser normalizes a negative start to unset and keeps the chapter)
    // and Matroska (a missing ChapterTimeStart) emit exactly that. Unfiltered it sorts first
    // and is "current" at every position, and tapping it hands seekTo an unset target.
    .filter { it.startTimeMs >= 0 }
    .mapNotNull { chapter ->
        // Nullable coming out of Java: a chapter carrying no label at all is a position marker,
        // not something worth listing.
        chapter.title?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { Chapter(startMs = chapter.startTimeMs, title = it) }
    }
    // Defensive, and knowingly untriggered today: an MP4 can carry the same chapters twice — a
    // Nero `chpl` atom *and* a QuickTime chapter track, which is what ffmpeg writes by default —
    // but media3 1.11.0 surfaces only one set. Verified 2026-08-27 by deleting this line and
    // re-running MediaChaptersTest against a fixture carrying both: still three chapters, not six.
    // Kept because "which format wins" is media3's to change, and a doubled list fails silently.
    .distinctBy { it.startMs }
    .sortedBy { it.startMs }
    .toList()
