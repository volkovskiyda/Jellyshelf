package com.gmail.volkovskiyda.jellyshelf.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.local.VideoEntity
import com.gmail.volkovskiyda.jellyshelf.util.DurationBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = container.libraryRepository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _durationFilter = MutableStateFlow<DurationBucket?>(null)
    val durationFilter: StateFlow<DurationBucket?> = _durationFilter.asStateFlow()

    val videos: StateFlow<List<VideoEntity>> =
        combine(_query, _durationFilter) { q, filter -> q to filter }
            .flatMapLatest { (q, filter) ->
                if (q.isBlank() && filter == null) repo.observeVideos()
                else repo.searchVideos(q, filter)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onDurationFilterChange(bucket: DurationBucket?) {
        _durationFilter.value = bucket
    }
}
