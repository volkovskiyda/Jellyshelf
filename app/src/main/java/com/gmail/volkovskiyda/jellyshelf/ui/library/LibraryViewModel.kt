package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.model.DurationBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * A videos emission tagged with whether it is the pristine list — no query, no duration filter.
 * The flag rides along with the list so the UI can't mistake the lingering results of a search it
 * just cleared (the query flips to blank a frame before the unfiltered list re-emits) for the
 * pristine list, which would restore the saved scroll position against the wrong contents.
 */
data class LibraryVideos(val items: List<Video>, val pristine: Boolean)

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
        repo.videoCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Null while the very first Room emission is pending, so the UI can tell loading from
     * empty. Seeded from the container-held last emission on recreation (tab switch), so a
     * revisit shows the previous list immediately instead of a loading flash.
     */
    val videos: StateFlow<LibraryVideos?> =
        combine(_query, _durationFilter) { q, filter -> q to filter }
            .flatMapLatest { (q, filter) ->
                val pristine = q.isBlank() && filter == null
                (if (pristine) repo.observeVideos() else repo.searchVideos(q, filter))
                    .map { LibraryVideos(it, pristine) }
            }
            .onEach { filters.lastVideos = it.items }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                filters.lastVideos?.let {
                    LibraryVideos(it, _query.value.isBlank() && _durationFilter.value == null)
                },
            )

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onDurationFilterChange(bucket: DurationBucket?) {
        _durationFilter.value = bucket
    }
}
