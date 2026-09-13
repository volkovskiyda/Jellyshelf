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
 * What the analytics call this destination.
 *
 * The class's own simple name, but written out as literals rather than read from `this::class`:
 * release builds are minified, and a reflected simple name would arrive in the console as whatever
 * R8 renamed the class to that build. Kotzilla's compiler plugin resolves the same name at compile
 * time for the routes it rewrites, so these strings are also what the automatic path would have
 * sent — keep them equal to the class names.
 */
fun AppNavKey.screenName(): String = when (this) {
    AppNavKey.Library -> "Library"
    AppNavKey.Categories -> "Categories"
    AppNavKey.Settings -> "Settings"
    is AppNavKey.CategoryVideos -> "CategoryVideos"
    is AppNavKey.Detail -> "Detail"
    is AppNavKey.Player -> "Player"
}

/**
 * The identifying arguments of this destination, for the analytics that record a screen visit.
 *
 * Ids only, never a title: enough to tell one visit apart from another in a session timeline,
 * without sending what is in the user's library to a third party. The destinations that carry no
 * argument carry none here either, and [PlayerOrigin] is left out — it says where the user came
 * from, which the sequence of visits already shows.
 *
 * Lives here rather than beside the analytics call because it names no analytics type: a checkout
 * with no Kotzilla keys has no SDK on the classpath at all, and everything in this file has to
 * compile there.
 */
fun AppNavKey.routeArgs(): Map<String, String> = when (this) {
    is AppNavKey.CategoryVideos -> mapOf("categoryId" to categoryId)
    is AppNavKey.Detail -> mapOf("youtubeId" to youtubeId)
    is AppNavKey.Player -> mapOf("youtubeId" to youtubeId)
    AppNavKey.Library, AppNavKey.Categories, AppNavKey.Settings -> emptyMap()
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
