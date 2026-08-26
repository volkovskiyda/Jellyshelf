package com.gmail.volkovskiyda.jellyshelf.domain.model

// Category dimension types. These string values are persisted in the database, so they must never
// change.
const val CATEGORY_TYPE_AUTO_CHANNEL = "AUTO_CHANNEL"
const val CATEGORY_TYPE_AUTO_YEAR = "AUTO_YEAR"
const val CATEGORY_TYPE_AUTO_MONTH = "AUTO_MONTH"
const val CATEGORY_TYPE_AUTO_DURATION = "AUTO_DURATION"
const val CATEGORY_TYPE_AUTO_YT_CATEGORY = "AUTO_YT_CATEGORY"
const val CATEGORY_TYPE_MANUAL = "MANUAL"

/** Synthetic type for the "Others" tab's virtual filters (see [VIRTUAL_CATEGORY_UNCATEGORIZED] etc.). */
const val CATEGORY_TYPE_OTHERS = "OTHERS"

// Virtual category ids for the "Others" tab. These are not stored rows — the repository routes
// them to live queries over the videos table, so they always reflect current state.
const val VIRTUAL_CATEGORY_UNCATEGORIZED = "virtual:uncategorized"
const val VIRTUAL_CATEGORY_WATCHED = "virtual:watched"
const val VIRTUAL_CATEGORY_UNWATCHED = "virtual:unwatched"
const val VIRTUAL_CATEGORY_CONTINUE = "virtual:continue"

/**
 * Videos the server has stopped listing — see [Video.missingFromServer]. Almost always videos
 * deleted on the server that the app hasn't been told about: sync counts missed appearances
 * rather than deleting on the first one, so they sit in the library looking real until the grace
 * period runs out. This filter is where they can be seen together and dropped in one go.
 */
const val VIRTUAL_CATEGORY_MISSING = "virtual:missing"

/** A category as the UI sees it. The data layer maps its Room row (`CategoryEntity`) onto this. */
data class Category(
    val id: String,
    val name: String,
    val type: String,
    val createdAt: Long,
)

/** A [Category] plus its member count, for the categories list. */
data class CategoryWithCount(
    val category: Category,
    val videoCount: Int,
)
