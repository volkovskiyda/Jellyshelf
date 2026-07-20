package com.gmail.volkovskiyda.jellyshelf.util

/**
 * Extracts the 11-char YouTube id embedded in a yt-dlp filename / Jellyfin path.
 * Prefers a bracketed "[VIDEOID]" group (yt-dlp default), then falls back to the last
 * standalone 11-char token in the file stem. The fallback requires the token to be delimited
 * (lookarounds), so a slice of a longer word can never be mistaken for an id.
 */
object YoutubeId {
    private val BRACKET = Regex("""\[([A-Za-z0-9_-]{11})]""")
    private val TOKEN = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])""")

    fun fromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = fileNameFromPath(path) ?: return null
        BRACKET.findAll(name).lastOrNull()?.let { return it.groupValues[1] }
        val stem = name.substringBeforeLast('.')
        return TOKEN.findAll(stem).lastOrNull()?.value
    }
}

/** Basename of a yt-dlp / Jellyfin path (strips directories); the library sort key. */
fun fileNameFromPath(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return path.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
}
