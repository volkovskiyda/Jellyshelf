package com.gmail.volkovskiyda.jellyshelf.ui.categories

import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Categories search state, owned by the app container: switching bottom-nav tabs clears the
 * tab's ViewModel store, and the query and search-all toggle must survive that (mirroring
 * [com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState]).
 */
class CategoriesFilterState {
    val query = MutableStateFlow("")
    val searchAll = MutableStateFlow(false)

    /**
     * The dimension (tab) the user last settled on. Container-owned for the same reason as the
     * query: bottom-nav tab switches clear the back stack and with it all rememberSaveable
     * state, and returning to Categories should land on the same dimension.
     */
    val selectedType = MutableStateFlow<String?>(null)

    /**
     * False until the persisted [selectedType] has been read back from disk on launch. The tab UI
     * defers tracking the pager's settled page until this flips true, so the async restore isn't
     * clobbered by the pager reporting its initial page 0 before the saved dimension is applied.
     */
    val selectionLoaded = MutableStateFlow(false)

    /**
     * Last lists emitted for the surviving query, seeding the recreated ViewModel so a
     * revisited tab renders instantly instead of flashing the loading state until Room
     * re-emits. Caching "others" too keeps that tab from vanishing for a frame on revisit.
     */
    @Volatile
    var lastCategories: List<CategoryWithCount>? = null

    @Volatile
    var lastOthers: List<CategoryWithCount>? = null
}
