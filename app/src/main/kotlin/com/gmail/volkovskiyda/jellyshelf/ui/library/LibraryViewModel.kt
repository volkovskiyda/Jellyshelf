package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.debounceSearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * A videos emission tagged with the query and duration filter that produced it. The terms ride
 * along with the list so the UI can't describe it in terms of narrowing that hasn't been applied
 * yet: the query flips (blank a frame before the unfiltered list re-emits, non-blank a whole
 * debounce before the matches arrive), so reading the live query would restore the saved scroll
 * position against the wrong contents and caption an empty result with the wrong reason.
 */
data class LibraryVideos(
    val items: List<Video>,
    val query: String,
    val durationFilter: DurationBucket?,
) {
    /** The unnarrowed list — no query, no duration filter. */
    val pristine: Boolean get() = query.isBlank() && durationFilter == null
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: LibraryRepository,
    // Singleton-owned so query and filter survive tab switches (which clear this ViewModel).
    private val filters: LibraryFilterState,
) : ViewModel() {
    private val _query = filters.query
    val query: StateFlow<String> = _query.asStateFlow()

    private val _durationFilter = filters.durationFilter
    val durationFilter: StateFlow<DurationBucket?> = _durationFilter.asStateFlow()

    /** Total videos in the library, independent of the current filter — the "all" denominator. */
    val totalCount: StateFlow<Int> =
        repo.videoCount().stateIn(viewModelScope, WhileUiSubscribed, 0)

    /**
     * Null while the very first Room emission is pending, so the UI can tell loading from
     * empty. Seeded from the container-held last emission on recreation (tab switch), so a
     * revisit shows the previous list immediately instead of a loading flash.
     */
    val videos: StateFlow<LibraryVideos?> =
        // Only the query is debounced — a duration chip tap is a single deliberate event and should
        // apply at once, so it stays on the raw flow.
        combine(_query.debounceSearchQuery(), _durationFilter) { q, filter -> q to filter }
            .flatMapLatest { (q, filter) ->
                val pristine = q.isBlank() && filter == null
                (if (pristine) repo.observeVideos() else repo.searchVideos(q, filter))
                    .map { LibraryVideos(it, q, filter) }
            }
            // Cached whole, so the seed below keeps the query and filter that actually produced
            // this list rather than whatever they read as at recreation time.
            .onEach { filters.lastVideos.value = it }
            .stateIn(viewModelScope, WhileUiSubscribed, filters.lastVideos.value)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onDurationFilterChange(bucket: DurationBucket?) = filters.setDurationFilter(bucket)
}
