package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A categories emission tagged with whether it is the pristine list — no query. The flag rides
 * along with the list so the UI can't mistake the lingering results of a search it just cleared
 * (the query blanks a frame before the unfiltered list re-emits) for the pristine list, which
 * would restore each tab's saved scroll position against the wrong contents.
 */
data class CategoryList(val items: List<CategoryWithCount>, val pristine: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(
    private val repo: LibraryRepository,
    // Singleton-owned so query and toggle survive tab switches (which clear this ViewModel).
    private val filters: CategoriesFilterState,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _query = filters.query
    val query: StateFlow<String> = _query.asStateFlow()

    /** When true, a query searches across every dimension instead of just the active tab. */
    private val _searchAll = filters.searchAll
    val searchAll: StateFlow<Boolean> = _searchAll.asStateFlow()

    /** The dimension tab the user last settled on; survives bottom-nav tab switches. */
    val selectedType: StateFlow<String?> = filters.selectedType.asStateFlow()

    /** False until the persisted selection has been read from disk on launch (see filter state). */
    val selectionLoaded: StateFlow<Boolean> = filters.selectionLoaded.asStateFlow()

    /**
     * Null while the very first Room emission is pending, so the UI can tell loading from
     * empty. Seeded from the container-held last emission on recreation (tab switch), so a
     * revisit shows the previous list immediately instead of a loading flash.
     */
    val categories: StateFlow<CategoryList?> = _query
        .flatMapLatest { q ->
            val pristine = q.isBlank()
            (if (pristine) repo.observeCategories() else repo.searchCategories(q))
                .map { CategoryList(it, pristine) }
        }
        .onEach { filters.lastCategories = it.items }
        .stateIn(
            viewModelScope,
            WhileUiSubscribed,
            filters.lastCategories?.let { CategoryList(it, _query.value.isBlank()) },
        )

    /** Virtual filters for the "Others" tab (Uncategorized / Continue / Unwatched / Watched). */
    val others: StateFlow<List<CategoryWithCount>> = repo.observeOthers()
        .onEach { filters.lastOthers = it }
        .stateIn(viewModelScope, WhileUiSubscribed, filters.lastOthers.orEmpty())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onSearchAllChange(value: Boolean) {
        _searchAll.value = value
    }

    fun onSelectedTypeChange(value: String) {
        if (filters.selectedType.value == value) return
        filters.selectedType.value = value
        // Persist so the tab is restored on next launch. Fire-and-forget: a failed write just
        // means the app reopens on the previous saved (or default) dimension.
        viewModelScope.launch { settingsRepository.setSelectedCategoryType(value) }
    }
}
