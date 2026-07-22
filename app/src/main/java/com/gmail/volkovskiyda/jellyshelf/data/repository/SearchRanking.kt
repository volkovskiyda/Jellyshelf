package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.data.local.RankedCategory
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity

/**
 * In-memory relevance ranking for the library and category searches. Both are a hard filter —
 * non-matching rows are dropped — plus a blended score sort: each searchable field contributes
 * `fieldWeight * matchTypeScore`, and a row's score is the best (max) of those. Higher scores
 * come first; file name / (type, name) breaks ties for a stable order.
 *
 * "Blended" means a strong hit in a lower-weight field can outrank a weak hit in a higher-weight
 * one — e.g. a channel that *starts with* the query beats a title that merely *contains* it.
 *
 * Multi-word queries are matched token-by-token with AND semantics: the query is split on
 * whitespace and *every* token must hit some field (not necessarily the same one, and not
 * necessarily adjacently). So "android edition" matches "Android Show IO Edition 2026" — the two
 * words land in the title without being contiguous — and the item's score is the sum of each
 * token's best field hit. A single-word query is just the one-token case and behaves as before.
 *
 * This deliberately replaces the old SQL `LIKE`-with-`CASE` ordering: substring matching (unlike
 * FTS5's token matching) is preserved, and expressing per-field weights combined with
 * exact/prefix/word-start/contains tiers is far clearer here than in SQL. The candidate sets are
 * the already-loaded lists, so scoring them costs nothing a personal library would notice.
 */
object SearchRanking {

    // Match-quality tiers, best to worst. A field scores its single best tier against the query.
    private const val EXACT = 100
    private const val PREFIX = 50
    private const val WORD_START = 30
    private const val CONTAINS = 10

    // Field weights (the multipliers). Videos: title > channel = description > tags = yt-categories.
    private const val WEIGHT_TITLE = 3
    private const val WEIGHT_CHANNEL = 2
    private const val WEIGHT_DESCRIPTION = 2
    private const val WEIGHT_TAG = 1
    private const val WEIGHT_YT_CATEGORY = 1

    // Categories: name > the description of a member video (which can only ever be a "contains").
    private const val WEIGHT_NAME = 3
    private const val WEIGHT_MEMBER_DESCRIPTION = 2

    private val WHITESPACE = Regex("\\s+")

    /**
     * The duration-filtered [videos] narrowed to those matching [rawQuery] and sorted most-relevant
     * first. A blank query is a no-op: the list is returned untouched (already file-name ordered).
     */
    fun rankVideos(rawQuery: String, videos: List<VideoEntity>): List<VideoEntity> {
        val tokens = tokenize(rawQuery)
        if (tokens.isEmpty()) return videos
        return videos
            .mapNotNull { video -> videoScore(video, tokens)?.let { video to it } }
            .sortedWith(
                compareByDescending<Pair<VideoEntity, Int>> { it.second }
                    .thenBy { it.first.fileName },
            )
            .map { it.first }
    }

    /**
     * The [categories] (each carrying a precomputed [RankedCategory.descriptionMatch]) narrowed to
     * those matching [rawQuery] and sorted most-relevant first, projected back to [CategoryWithCount]
     * for the UI. A blank query returns every row (only the projection changes).
     */
    fun rankCategories(rawQuery: String, categories: List<RankedCategory>): List<CategoryWithCount> {
        val tokens = tokenize(rawQuery)
        if (tokens.isEmpty()) return categories.map { it.toCategoryWithCount() }
        return categories
            .mapNotNull { category -> categoryScore(category, tokens)?.let { category to it } }
            .sortedWith(
                compareByDescending<Pair<RankedCategory, Int>> { it.second }
                    .thenBy { it.first.category.type }
                    .thenBy { it.first.category.name.lowercase() },
            )
            .map { it.first.toCategoryWithCount() }
    }

    /**
     * A video's blended score, or null if any [tokens] matches no field at all (AND semantics —
     * the video isn't a result). Each token contributes its single best `fieldWeight * tier` hit.
     */
    private fun videoScore(video: VideoEntity, tokens: List<String>): Int? {
        var total = 0
        for (token in tokens) {
            val best = maxOf(
                WEIGHT_TITLE * matchScore(video.title, token),
                WEIGHT_CHANNEL * matchScore(video.channel, token),
                WEIGHT_DESCRIPTION * matchScore(video.description, token),
                WEIGHT_TAG * matchScore(video.tags, token),
                WEIGHT_YT_CATEGORY * matchScore(video.youtubeCategories, token),
            )
            if (best == 0) return null
            total += best
        }
        return total
    }

    /**
     * A category's score, or null if it matches no token. The name is scored token-by-token (every
     * token must hit the name); failing that, a category still qualifies through
     * [RankedCategory.descriptionMatch] — a member video's description. That flag is phrase-level
     * (the whole query, computed in SQL), so for multi-word queries the description path is a
     * fallback rather than per-token, which is fine for the coarse "contains" signal it is.
     */
    private fun categoryScore(category: RankedCategory, tokens: List<String>): Int? {
        var nameTotal = 0
        for (token in tokens) {
            val score = WEIGHT_NAME * matchScore(category.category.name, token)
            if (score == 0) {
                nameTotal = 0
                break
            }
            nameTotal += score
        }
        if (nameTotal > 0) return nameTotal
        return if (category.descriptionMatch) WEIGHT_MEMBER_DESCRIPTION * CONTAINS else null
    }

    /** The query lower-cased and split into whitespace-separated tokens (empty ones dropped). */
    private fun tokenize(rawQuery: String): List<String> =
        rawQuery.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Best match tier of any element (0 when none match). */
    private fun matchScore(values: List<String>, query: String): Int =
        values.maxOfOrNull { matchScore(it, query) } ?: 0

    /**
     * Match tier of [query] (already trimmed and lower-cased) against a single [field]: exact whole
     * field, a prefix of it, the start of a word within it, an interior substring, or no match.
     */
    private fun matchScore(field: String?, query: String): Int {
        if (field.isNullOrEmpty()) return 0
        val value = field.lowercase()
        return when {
            value == query -> EXACT
            value.startsWith(query) -> PREFIX
            startsWord(value, query) -> WORD_START
            value.contains(query) -> CONTAINS
            else -> 0
        }
    }

    /** True when [query] begins a word in [value] — i.e. sits at a non-alphanumeric boundary. */
    private fun startsWord(value: String, query: String): Boolean {
        var i = value.indexOf(query)
        while (i >= 0) {
            if (i == 0 || !value[i - 1].isLetterOrDigit()) return true
            i = value.indexOf(query, i + 1)
        }
        return false
    }

    private fun RankedCategory.toCategoryWithCount() = CategoryWithCount(category, videoCount)
}
