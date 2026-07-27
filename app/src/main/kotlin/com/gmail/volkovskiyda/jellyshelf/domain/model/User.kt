package com.gmail.volkovskiyda.jellyshelf.domain.model

/** A Jellyfin user, as the Settings UI picks from. Mapped from the network `UserDto`. */
data class User(
    val id: String,
    val name: String,
)

/**
 * A successful password sign-in: the user-scoped token and the user it belongs to. The password
 * that produced it is deliberately absent — it is discarded the moment this comes back.
 */
data class Session(
    val accessToken: String,
    val user: User,
)
