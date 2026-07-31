package com.gmail.volkovskiyda.jellyshelf.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed class AppNavKey : NavKey {
    @Serializable
    data object Library : AppNavKey()

    @Serializable
    data object Categories : AppNavKey()

    @Serializable
    data object Settings : AppNavKey()

    @Serializable
    data class CategoryVideos(val categoryId: String, val title: String) : AppNavKey()

    @Serializable
    data class Detail(val youtubeId: String, val origin: PlayerOrigin? = null) : AppNavKey()

    @Serializable
    data class Player(val youtubeId: String, val origin: PlayerOrigin? = null) : AppNavKey()
}

/**
 * The list a video was opened from, carried to the player so previous/next and auto-advance
 * follow the order the user was browsing rather than the whole library.
 *
 * A sealed hierarchy of data objects/classes rather than an id plus a magic string, because the
 * back stack is persisted as JSON and this rides along inside it. Every case is optional
 * (`origin = null`): the media-notification path has no list context, and a stack persisted
 * before this existed decodes with the default — which is what keeps an in-place upgrade from
 * crashing on launch.
 */
@Serializable
sealed class PlayerOrigin {
    /** The library tab, as the user had it narrowed at the time. */
    @Serializable
    data object Library : PlayerOrigin()

    /** One category's videos. */
    @Serializable
    data class Category(val categoryId: String) : PlayerOrigin()
}
