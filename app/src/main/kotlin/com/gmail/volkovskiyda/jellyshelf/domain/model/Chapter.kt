package com.gmail.volkovskiyda.jellyshelf.domain.model

import kotlinx.serialization.Serializable

/**
 * A named position in a video — "0:00 Intro". Parsed from description timecodes
 * (`util/Timecodes.kt`) or carried as structured yt-dlp chapter data; description-parsed wins
 * when both exist. `@Serializable` from birth: structured chapters persist through the app's
 * JSON-in-Room converter pattern, and annotating now means the model never needs touching again.
 */
@Serializable
data class Chapter(val startMs: Long, val title: String)
