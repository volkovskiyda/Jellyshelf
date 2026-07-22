package com.gmail.volkovskiyda.jellyshelf.domain.model

/** A Jellyfin user, as the Settings UI picks from. Mapped from the network `UserDto`. */
data class User(
    val id: String,
    val name: String,
)
