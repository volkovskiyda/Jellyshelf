package com.gmail.volkovskiyda.jellyshelf.navigation

import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Detail
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Library
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey.Player
import org.junit.Assert.assertEquals
import org.junit.Test

private const val OPENED = "aaaaaaaaaaa"
private const val NEXT = "bbbbbbbbbbb"
private const val READING = "xxxxxxxxxxx"

/**
 * The stack rewrite behind the player's minimize, tested as a pure function because otherwise it
 * is only observable through real navigation: a wrong answer lands on the page of a video that is
 * no longer playing, drops a page the user was reading, or grows the stack by one Detail on every
 * play-and-minimize cycle — none of which any other test would notice.
 */
class MinimizeToDetailsTest {

    /** Today's plain pop, unchanged: still on the video whose page Play was pressed on. */
    @Test
    fun fromItsDetail_stillOnThatVideo_landsOnIt() {
        val stack = listOf(Library, Detail(OPENED), Player(OPENED, PlayerOrigin.Library))

        assertEquals(listOf(Library, Detail(OPENED)), minimizedBackStack(stack, OPENED))
    }

    /** The page Play was pressed on follows the queue rather than describing the previous video. */
    @Test
    fun fromItsDetail_afterTheQueueAdvanced_replacesThatDetail() {
        val stack = listOf(Library, Detail(OPENED), Player(OPENED, PlayerOrigin.Library))

        assertEquals(listOf(Library, Detail(NEXT)), minimizedBackStack(stack, NEXT))
    }

    @Test
    fun fromALibraryRow_landsOnTheDetailOfThePlayingVideo() {
        val stack = listOf(Library, Player(OPENED, PlayerOrigin.Library))

        assertEquals(listOf(Library, Detail(OPENED)), minimizedBackStack(stack, OPENED))
    }

    @Test
    fun fromALibraryRow_afterTheQueueAdvanced_landsOnTheNewVideosDetail() {
        val stack = listOf(Library, Player(OPENED, PlayerOrigin.Library))

        assertEquals(listOf(Library, Detail(NEXT)), minimizedBackStack(stack, NEXT))
    }

    /** Reopened from the bar or the notification over a page of another video: that page stays. */
    @Test
    fun reopenedOverAnUnrelatedDetail_keepsIt() {
        val stack = listOf(Library, Detail(READING), Player(NEXT))

        assertEquals(
            listOf(Library, Detail(READING), Detail(OPENED)),
            minimizedBackStack(stack, OPENED),
        )
    }

    @Test
    fun reopenedOverTheDetailOfWhatIsNowPlaying_landsOnIt() {
        val stack = listOf(Library, Detail(READING), Player(NEXT))

        assertEquals(listOf(Library, Detail(READING)), minimizedBackStack(stack, READING))
    }

    /** Two adjacent Details of one video would collide as NavEntry keys, so the existing one wins. */
    @Test
    fun replacingTheLaunchDetail_neverLeavesTwoDetailsOfOneVideoAdjacent() {
        val stack = listOf(
            Library,
            Detail(NEXT),
            Detail(OPENED),
            Player(OPENED, PlayerOrigin.Library),
        )

        assertEquals(listOf(Library, Detail(NEXT)), minimizedBackStack(stack, NEXT))
    }

    @Test
    fun withoutAPlayerOnTop_theStackIsUnchanged() {
        val stack = listOf(Library, Detail(OPENED))

        assertEquals(stack, minimizedBackStack(stack, NEXT))
    }
}
