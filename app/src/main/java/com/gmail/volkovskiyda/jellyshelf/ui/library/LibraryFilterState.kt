package com.gmail.volkovskiyda.jellyshelf.ui.library

import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Library search/filter state, owned by the app container: switching bottom-nav tabs clears the
 * tab's ViewModel store, and the query and duration filter must survive that (like the scroll
 * position already does).
 */
class LibraryFilterState {
    val query = MutableStateFlow("")
    val durationFilter = MutableStateFlow<DurationBucket?>(null)
}
