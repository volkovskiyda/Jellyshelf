package com.gmail.volkovskiyda.jellyshelf.ui.library

import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Library search/filter state, a process-lifetime singleton: switching bottom-nav tabs clears the
 * tab's ViewModel store, and the query and duration filter must survive that (like the scroll
 * position already does).
 */
class LibraryFilterState {
    val query = MutableStateFlow("")
    val durationFilter = MutableStateFlow<DurationBucket?>(null)

    /**
     * Last emission, seeding the recreated ViewModel so a revisited tab renders instantly instead
     * of flashing the loading state until Room re-emits.
     *
     * The whole emission, not just its items: it already carries the query and filter that produced
     * it, and re-deriving those from [query]/[durationFilter] at seed time would read them as of
     * *recreation*, which is a different moment. A ViewModel cleared between a query change and the
     * debounced emission for it would then seed the old list tagged with the new query — and the
     * scroll restore, which trusts that tag, would restore a position onto contents it never
     * belonged to.
     */
    val lastVideos = MutableStateFlow<LibraryVideos?>(null)
}
