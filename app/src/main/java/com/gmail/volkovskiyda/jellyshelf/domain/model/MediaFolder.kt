package com.gmail.volkovskiyda.jellyshelf.domain.model

/** A folder in the Jellyfin item tree, as the Settings folder browser shows. Mapped from `BaseItemDto`. */
data class MediaFolder(
    val id: String,
    val name: String,
    val path: String?,
)
