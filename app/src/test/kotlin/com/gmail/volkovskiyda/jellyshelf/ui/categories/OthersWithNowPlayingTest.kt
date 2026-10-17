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

    /** Unlike the library's filters, it is where the queue lives, so it stays listed while empty. */
    @Test
    fun `an empty queue is still listed beside the library's filters`() {
        val others = othersWithNowPlaying(listOf(watched), queued = 0)

        assertEquals(0, others.single { it.category.id == VIRTUAL_CATEGORY_NOW_PLAYING }.videoCount)
    }

    @Test
    fun `an empty library with nothing queued keeps its empty screen`() {
        assertEquals(emptyList<CategoryWithCount>(), othersWithNowPlaying(emptyList(), queued = 0))
    }
}
