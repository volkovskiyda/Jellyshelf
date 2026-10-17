package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.model.VIRTUAL_CATEGORY_NOW_PLAYING
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.domain.repository.SettingsRepository
import com.gmail.volkovskiyda.jellyshelf.playback.NowPlayingQueue
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.debounceSearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val nowPlayingQueue: NowPlayingQueue,
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
    val categories: StateFlow<CategoryList?> = _query.debounceSearchQuery()
        .flatMapLatest { q ->
            val pristine = q.isBlank()
            (if (pristine) repo.observeCategories() else repo.searchCategories(q))
                .map { CategoryList(it, pristine) }
        }
        // Cached whole, so the seed below keeps the pristine-ness that actually produced this list
        // rather than recomputing it from the query as it reads at recreation time.
        .onEach { filters.lastCategories.value = it }
        .stateIn(viewModelScope, WhileUiSubscribed, filters.lastCategories.value)

    /**
     * Virtual filters for the "Others" tab: Now playing first, then the library's own (see
     * [LibraryRepository.observeOthers]).
     *
     * Now playing is joined here rather than in the repository because it is not library data: its
     * count is the playback session's queue, which the service publishes in [NowPlayingQueue]. It is
     * listed while empty, unlike the library's filters — it is where the queue is, playing or not —
     * except on a library with no Others at all, whose empty Categories screen it would only clutter.
     */
    val others: StateFlow<List<CategoryWithCount>> = combine(
        repo.observeOthers(),
        nowPlayingQueue.items.map { it.size }.distinctUntilChanged(),
    ) { library, queued -> othersWithNowPlaying(library, queued) }
        .onEach { filters.lastOthers.value = it }
        .stateIn(viewModelScope, WhileUiSubscribed, filters.lastOthers.value.orEmpty())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onSearchAllChange(value: Boolean) = filters.setSearchAll(value)

    fun onSelectedTypeChange(value: String) {
        if (filters.selectedType.value == value) return
        filters.selectedType.value = value
        // Persist so the tab is restored on next launch. Fire-and-forget: a failed write just
        // means the app reopens on the previous saved (or default) dimension.
        viewModelScope.launch { settingsRepository.setSelectedCategoryType(value) }
    }
}

/**
 * The Others tab's rows: Now playing, counting [queued] videos, ahead of the [library]'s own
 * filters — or nothing at all when both are empty, so an empty library keeps its empty screen.
 */
internal fun othersWithNowPlaying(library: List<CategoryWithCount>, queued: Int): List<CategoryWithCount> {
    if (library.isEmpty() && queued == 0) return library
    val nowPlaying = CategoryWithCount(
        category = Category(
            id = VIRTUAL_CATEGORY_NOW_PLAYING,
            name = "Now playing",
            type = CATEGORY_TYPE_OTHERS,
            createdAt = 0L,
        ),
        videoCount = queued,
    )
    return listOf(nowPlaying) + library
}
