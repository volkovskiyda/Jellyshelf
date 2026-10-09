package com.gmail.volkovskiyda.jellyshelf.ui

import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The column counts the video grid is meant to give the windows the app runs in, and the cover a
 * cell of each gets. The widths are what the grid itself receives: the window, less the 80 dp rail
 * from a medium window up.
 */
class VideoGridTest {

    @Test
    fun phoneUpright_isOneColumn() {
        assertEquals(1, videoGridColumns(360.dp))
        assertEquals(1, videoGridColumns(430.dp))
    }

    @Test
    fun phoneOnItsSide_isTwoColumns() {
        assertEquals(2, videoGridColumns(720.dp)) // an 800 dp-wide phone
        assertEquals(2, videoGridColumns(812.dp)) // a large phone, 892 dp
    }

    @Test
    fun tablet_isTwoColumnsEitherWayUp() {
        assertEquals(2, videoGridColumns(720.dp)) // upright, 800 dp
        assertEquals(2, videoGridColumns(1200.dp)) // on its side, 1,280 dp
    }

    @Test
    fun desktopWindow_isThreeColumns() {
        assertEquals(3, videoGridColumns(1840.dp))
    }

    /** Just past one maximum cell: two halves would be narrower than a phone, so it stays one. */
    @Test
    fun widthJustPastOneCell_staysOneColumn() {
        assertEquals(1, videoGridColumns(660.dp))
    }

    @Test
    fun cover_isTheSameShareOfEveryCell() {
        assertEquals(240.dp, videoThumbnailWidth(600.dp)) // the landscape tablet's hand-picked size
        assertEquals(160.dp, videoThumbnailWidth(400.dp))
        assertEquals(144.dp, videoThumbnailWidth(360.dp))
    }
}
