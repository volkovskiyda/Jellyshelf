package com.gmail.volkovskiyda.jellyshelf.ui.categories

import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_NOW_PLAYING
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_WATCHED
import org.junit.Assert.assertEquals
import org.junit.Test

class OthersWithNowPlayingTest {

    private val watched = CategoryWithCount(Category(VIRTUAL_CATEGORY_WATCHED, "Watched", CATEGORY_TYPE_OTHERS, 0L), 4)

    @Test
    fun `Now playing leads the list and counts the whole queue`() {
        val others = othersWithNowPlaying(listOf(watched), queued = 12)

        assertEquals(listOf(VIRTUAL_CATEGORY_NOW_PLAYING, VIRTUAL_CATEGORY_WATCHED), others.map { it.category.id })
        assertEquals(12, others.first().videoCount)
    }

    /** Hidden while empty, as every other filter is. */
    @Test
    fun `nothing queued leaves Now playing out`() {
        assertEquals(listOf(watched), othersWithNowPlaying(listOf(watched), queued = 0))
        assertEquals(emptyList<CategoryWithCount>(), othersWithNowPlaying(emptyList(), queued = 0))
    }

    @Test
    fun `a queue lists Now playing even on an otherwise empty Others tab`() {
        val others = othersWithNowPlaying(emptyList(), queued = 3)

        assertEquals(listOf(VIRTUAL_CATEGORY_NOW_PLAYING), others.map { it.category.id })
    }
}
