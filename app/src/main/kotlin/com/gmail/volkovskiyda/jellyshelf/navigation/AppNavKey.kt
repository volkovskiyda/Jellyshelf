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
    data class Detail(val youtubeId: String) : AppNavKey()

    @Serializable
    data class Player(val youtubeId: String) : AppNavKey()
}
