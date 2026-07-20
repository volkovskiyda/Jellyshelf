package com.gmail.volkovskiyda.jellyshelf.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = container.libraryRepository

    // Container-owned so query and filter survive tab switches (which clear this ViewModel).
    private val filters = container.libraryFilterState
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
    val videos: StateFlow<List<VideoEntity>?> =
        combine(_query, _durationFilter) { q, filter -> q to filter }
            .flatMapLatest { (q, filter) ->
                if (q.isBlank() && filter == null) repo.observeVideos()
                else repo.searchVideos(q, filter)
            }
            .onEach { filters.lastVideos = it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), filters.lastVideos)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onDurationFilterChange(bucket: DurationBucket?) {
        _durationFilter.value = bucket
    }
}
