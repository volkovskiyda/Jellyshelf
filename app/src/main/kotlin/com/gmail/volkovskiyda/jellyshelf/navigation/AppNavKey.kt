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
    data class Detail(
        val youtubeId: String,
        val origin: PlayerOrigin = PlayerOrigin.None,
    ) : AppNavKey()

    @Serializable
    data class Player(
        val youtubeId: String,
        val origin: PlayerOrigin = PlayerOrigin.None,
    ) : AppNavKey()
}

/**
 * The list a video was opened from, carried to the player so previous/next and auto-advance
 * follow the order the user was browsing rather than the whole library.
 *
 * A sealed hierarchy of data objects/classes rather than an id plus a magic string, because the
 * back stack is persisted as JSON and this rides along inside it. "No list" is [None] rather than
 * a null, so every consumer handles it as one more case of the same `when` — and so Koin can
 * type-match it as a `parametersOf` argument, which a null can never be.
 */
@Serializable
sealed class PlayerOrigin {
    /**
     * No list context: the media-notification path, and the default a back stack persisted
     * before origins existed decodes with. Plays the one video alone, both transport buttons
     * disabled — exactly the behavior the player had before queues.
     */
    @Serializable
    data object None : PlayerOrigin()

    /** The library tab, as the user had it narrowed at the time. */
    @Serializable
    data object Library : PlayerOrigin()

    /** One category's videos. */
    @Serializable
    data class Category(val categoryId: String) : PlayerOrigin()
}
