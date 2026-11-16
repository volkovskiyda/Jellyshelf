@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * The container a direct-play stream is in, for the in-app player's media items — named so media3
 * does not have to work it out.
 *
 * Every queued item goes through `DefaultMediaSourceFactory`, which picks an HLS, DASH or plain
 * progressive source from the item's MIME type — or, with none set, from its URL: the URL is parsed
 * and its path run through a regex for Smooth Streaming manifests, because a Jellyfin stream URL
 * ends in `/stream` and has no extension to go by. That is ~20 µs per item, all of it on the main
 * thread inside `setMediaItems`, and a library-sized queue (1,245 videos) spent ~26 ms of a ~110 ms
 * main-thread block on it before the first byte was requested — measured 2026-11-15 on a mid-range
 * API 34 phone. Any non-adaptive type sends an item straight down the progressive path; the real
 * container is named rather than a placeholder so the value is true wherever else it is read.
 *
 * The extension is the server file's, which `static=true` streams untouched. Null for one not listed
 * here, which media3 then infers from the URL as before.
 */
internal fun containerMimeType(fileName: String): String? =
    when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> MimeTypes.VIDEO_MP4
        "mkv" -> MimeTypes.VIDEO_MATROSKA
        "webm" -> MimeTypes.VIDEO_WEBM
        else -> null
    }
