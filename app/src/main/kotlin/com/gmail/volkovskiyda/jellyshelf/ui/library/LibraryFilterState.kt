package com.gmail.volkovskiyda.jellyshelf.ui.library

import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Library search/filter state, a process-lifetime singleton: switching bottom-nav tabs clears the
 * tab's ViewModel store, and the query and duration filter must survive that (like the scroll
 * position already does).
 *
 * The duration filter survives more than that — it is persisted, so it is still applied after a
 * background kill. The query is deliberately not: a search box that comes back filled is not what
 * users expect, and persisting it would also suppress the pristine scroll restore on every launch.
 */
class LibraryFilterState(
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) {
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

    /**
     * Applies the filter and persists it. The write lives here rather than in the ViewModel
     * because the scope has to outlive one: a bottom-nav tab switch clears the `ViewModelStore`,
     * which would cancel a `viewModelScope` write mid-flight. Fire-and-forget — a failed write
     * just means the app reopens on the previously saved filter.
     */
    fun setDurationFilter(bucket: DurationBucket?) {
        durationFilter.value = bucket
        dispatchers.applicationScope.launch { settingsRepository.setLibraryDurationFilter(bucket) }
    }

    init {
        // Async, best-effort restore, with the same don't-clobber guard CategoriesFilterState
        // uses: a filter the user picked before the read landed wins.
        //
        // No "restored" flag to gate the UI on, unlike the Categories dimension: nothing here
        // races the scroll restore. A restored filter simply makes the library open non-pristine,
        // and LibraryScreen already declines to apply the saved offset in that case — correctly,
        // since that offset belongs to the unfiltered list.
        dispatchers.applicationScope.launch {
            val persisted = settingsRepository.libraryDurationFilter.first()
            if (persisted != null && durationFilter.value == null) {
                durationFilter.value = persisted
            }
        }
    }
}
