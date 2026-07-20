package com.gmail.volkovskiyda.jellyshelf.util

/**
 * Extracts the 11-char YouTube id embedded in a yt-dlp filename / Jellyfin path.
 * Prefers a bracketed "[VIDEOID]" group (yt-dlp default), then falls back to the
 * last standalone 11-char token in the file stem.
 */
object YoutubeId {
    private val BRACKET = Regex("""\[([A-Za-z0-9_-]{11})]""")
    private val TOKEN = Regex("""[A-Za-z0-9_-]{11}""")

    fun fromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        BRACKET.findAll(name).lastOrNull()?.let { return it.groupValues[1] }
        val stem = name.substringBeforeLast('.')
        return TOKEN.findAll(stem).lastOrNull()?.value
    }
}
