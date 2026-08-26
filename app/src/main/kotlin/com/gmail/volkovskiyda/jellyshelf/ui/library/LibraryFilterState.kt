package com.gmail.volkovskiyda.jellyshelf.ui.library

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Library search state, a process-lifetime singleton: switching bottom-nav tabs clears the tab's
 * ViewModel store, and the query must survive that (like the scroll position already does).
 *
 * Nothing here is persisted. The query deliberately is not: a search box that comes back filled is
 * not what users expect, and persisting it would also suppress the pristine scroll restore on
 * every launch.
 */
class LibraryFilterState {
    val query = MutableStateFlow("")

    /**
     * Last emission, seeding the recreated ViewModel so a revisited tab renders instantly instead
     * of flashing the loading state until Room re-emits.
     *
     * The whole emission, not just its items: it already carries the query that produced it, and
     * re-deriving that from [query] at seed time would read it as of *recreation*, which is a
     * different moment. A ViewModel cleared between a query change and the debounced emission for
     * it would then seed the old list tagged with the new query — and the scroll restore, which
     * trusts that tag, would restore a position onto contents it never belonged to.
     */
    val lastVideos = MutableStateFlow<LibraryVideos?>(null)
}
