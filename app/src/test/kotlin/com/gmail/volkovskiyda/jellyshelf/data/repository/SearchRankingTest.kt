package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryEntity
import com.gmail.volkovskiyda.jellyshelf.data.local.RankedCategory
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankingTest {

    private fun video(
        id: String,
        title: String,
        channel: String? = null,
        description: String? = null,
        tags: List<String> = emptyList(),
        youtubeCategories: List<String> = emptyList(),
        fileName: String = id,
    ) = VideoEntity(
        youtubeId = id,
        jellyfinItemId = null,
        fileName = fileName,
        title = title,
        channel = channel,
        channelId = null,
        durationSeconds = 0,
        uploadDate = null,
        description = description,
        tags = tags,
        youtubeCategories = youtubeCategories,
        thumbnailUrl = null,
        played = false,
        playbackPositionTicks = 0,
        playCount = 0,
        lastSyncedAt = 0,
    )

    private fun idsRanked(query: String, vararg videos: VideoEntity): List<String> =
        SearchRanking.rankVideos(query, videos.toList()).map { it.youtubeId }

    @Test
    fun `blank query returns the list unchanged`() {
        val list = listOf(video("b", "Beta"), video("a", "Alpha"))
        assertEquals(list, SearchRanking.rankVideos("   ", list))
    }

    @Test
    fun `non-matches are dropped`() {
        val ids = idsRanked(
            "kotlin",
            video("hit", "Kotlin coroutines"),
            video("miss", "Rust ownership"),
        )
        assertEquals(listOf("hit"), ids)
    }

    @Test
    fun `search is case-insensitive`() {
        assertEquals(listOf("hit"), idsRanked("KOTLIN", video("hit", "kotlin basics")))
    }

    @Test
    fun `match tiers order exact then prefix then word-start then contains`() {
        val ids = idsRanked(
            "cat",
            video("contains", "concatenate"),
            video("exact", "cat"),
            video("word", "the cat sat"),
            video("prefix", "category theory"),
        )
        assertEquals(listOf("exact", "prefix", "word", "contains"), ids)
    }

    @Test
    fun `title outranks a channel-only match at the same tier`() {
        val ids = idsRanked(
            "focus",
            video("channelOnly", "Deep work", channel = "Focus"),
            video("titleMatch", "Focus", channel = "Someone"),
        )
        // Both are exact-tier, but title's weight (3) beats channel's (2).
        assertEquals(listOf("titleMatch", "channelOnly"), ids)
    }

    @Test
    fun `blended scoring lets a strong channel hit beat a weak title hit`() {
        val ids = idsRanked(
            "sci",
            video("titleContains", "Conscious mind"), // title contains: 3 * 10 = 30
            video("channelPrefix", "Anything", channel = "Science weekly"), // channel prefix: 2 * 50 = 100
        )
        assertEquals(listOf("channelPrefix", "titleContains"), ids)
    }

    @Test
    fun `tags and youtube categories are searchable but weighted low`() {
        val ids = idsRanked(
            "music",
            video("tagOnly", "Untitled session", tags = listOf("ambient music")),
            video("titleWord", "Live music set"),
        )
        // title word-start (3 * 30 = 90) outranks tag word-start (1 * 30 = 30); both survive the filter.
        assertEquals(listOf("titleWord", "tagOnly"), ids)
    }

    @Test
    fun `multi-word query matches non-adjacent tokens in the title`() {
        // The reported case: "android edition" is not a contiguous substring of the title.
        assertEquals(
            listOf("hit"),
            idsRanked("android edition", video("hit", "Android Show IO Edition 2026")),
        )
    }

    @Test
    fun `multi-word query requires every token to match (AND)`() {
        val ids = idsRanked(
            "android edition",
            video("both", "Android Show IO Edition 2026"),
            video("androidOnly", "Android basics tutorial"),
        )
        assertEquals(listOf("both"), ids)
    }

    @Test
    fun `tokens may match across different fields`() {
        val ids = idsRanked(
            "kotlin google",
            video("split", "Kotlin coroutines", channel = "Google Developers"),
            video("titleOnly", "Kotlin coroutines", channel = "Someone else"),
        )
        assertEquals(listOf("split"), ids)
    }

    @Test
    fun `ties break by file name`() {
        val ids = idsRanked(
            "talk",
            video("z", "Talk", fileName = "z.mp4"),
            video("a", "Talk", fileName = "a.mp4"),
        )
        assertEquals(listOf("a", "z"), ids)
    }

    private fun category(id: String, name: String, descriptionMatch: Boolean = false) =
        RankedCategory(
            category = CategoryEntity(id = id, name = name, type = CATEGORY_TYPE_AUTO_CHANNEL, createdAt = 0),
            videoCount = 1,
            descriptionMatch = descriptionMatch,
        )

    @Test
    fun `category name match outranks a member-description match`() {
        val ranked = SearchRanking.rankCategories(
            "jazz",
            listOf(
                category("descOnly", "Playlists", descriptionMatch = true),
                category("nameMatch", "Jazz", descriptionMatch = false),
            ),
        )
        assertEquals(listOf("nameMatch", "descOnly"), ranked.map { it.category.id })
    }

    @Test
    fun `category with neither name nor description match is dropped`() {
        val ranked = SearchRanking.rankCategories(
            "jazz",
            listOf(
                category("keep", "Jazz"),
                category("drop", "Rock", descriptionMatch = false),
            ),
        )
        assertEquals(listOf("keep"), ranked.map { it.category.id })
    }
}
