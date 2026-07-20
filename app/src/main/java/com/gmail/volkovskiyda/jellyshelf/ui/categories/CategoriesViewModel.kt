package com.gmail.volkovskiyda.jellyshelf.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.container
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = container.libraryRepository

    // Container-owned so query and toggle survive tab switches (which clear this ViewModel).
    private val filters = container.categoriesFilterState
    private val _query = filters.query
    val query: StateFlow<String> = _query.asStateFlow()

    /** When true, a query searches across every dimension instead of just the active tab. */
    private val _searchAll = filters.searchAll
    val searchAll: StateFlow<Boolean> = _searchAll.asStateFlow()

    /**
     * Null while the very first Room emission is pending, so the UI can tell loading from
     * empty. Seeded from the container-held last emission on recreation (tab switch), so a
     * revisit shows the previous list immediately instead of a loading flash.
     */
    val categories: StateFlow<List<CategoryWithCount>?> = _query
        .flatMapLatest { q ->
            if (q.isBlank()) repo.observeCategories() else repo.searchCategories(q)
        }
        .onEach { filters.lastCategories = it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), filters.lastCategories)

    /** Virtual filters for the "Others" tab (Uncategorized / Continue / Unwatched / Watched). */
    val others: StateFlow<List<CategoryWithCount>> = repo.observeOthers()
        .onEach { filters.lastOthers = it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), filters.lastOthers.orEmpty())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onSearchAllChange(value: Boolean) {
        _searchAll.value = value
    }
}
