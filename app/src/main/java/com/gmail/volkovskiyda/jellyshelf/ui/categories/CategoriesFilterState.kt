package com.gmail.volkovskiyda.jellyshelf.ui.categories

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Categories search state, owned by the app container: switching bottom-nav tabs clears the
 * tab's ViewModel store, and the query and search-all toggle must survive that (mirroring
 * [com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryFilterState]).
 */
class CategoriesFilterState {
    val query = MutableStateFlow("")
    val searchAll = MutableStateFlow(false)
}
