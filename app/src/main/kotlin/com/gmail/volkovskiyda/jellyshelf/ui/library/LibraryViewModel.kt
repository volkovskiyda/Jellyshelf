package com.gmail.volkovskiyda.jellyshelf.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gmail.volkovskiyda.jellyshelf.domain.AppSettingsState
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import com.gmail.volkovskiyda.jellyshelf.domain.repository.LibraryRepository
import com.gmail.volkovskiyda.jellyshelf.ui.WhileUiSubscribed
import com.gmail.volkovskiyda.jellyshelf.ui.debounceSearchQuery
import com.gmail.volkovskiyda.jellyshelf.ui.selection.VideoSelectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * A videos emission tagged with the query that produced it. The query rides along with the list so
 * the UI can't describe it in terms of a search that hasn't been applied yet: the live query flips
 * (blank a frame before the unfiltered list re-emits, non-blank a whole debounce before the
 * matches arrive), so reading it would restore the saved scroll position against the wrong
 * contents and caption an empty result with the wrong reason.
 */
data class LibraryVideos(val items: List<Video>, val query: String) {
    /** The unnarrowed list — no query. */
    val pristine: Boolean get() = query.isBlank()
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: LibraryRepository,
    // Singleton-owned so the query survives tab switches (which clear this ViewModel).
    private val filters: LibraryFilterState,
    settingsState: AppSettingsState,
) : ViewModel() {
    /**
     * Multi-select. Owned by the ViewModel and not by the singleton the filters live in: a
     * selection is a short deliberate mode, and leaving the tab is a clear enough signal that the
     * user is done with it. The run it starts lives on the repository and outlives both.
     */
    val selection = VideoSelectionController(repo, viewModelScope)

    /** Only the removal's confirmation reads this — see the dialog for what it changes. */
    val demoMode: StateFlow<Boolean> = settingsState.settings
        .map { it?.demoMode == true }
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    private val _query = filters.query
    val query: StateFlow<String> = _query.asStateFlow()

    /** Total videos in the library, independent of the current search — the "all" denominator. */
    val totalCount: StateFlow<Int> =
        repo.videoCount().stateIn(viewModelScope, WhileUiSubscribed, 0)

    /**
     * Null while the very first Room emission is pending, so the UI can tell loading from
     * empty. Seeded from the container-held last emission on recreation (tab switch), so a
     * revisit shows the previous list immediately instead of a loading flash.
     */
    val videos: StateFlow<LibraryVideos?> =
        _query.debounceSearchQuery()
            .flatMapLatest { q ->
                (if (q.isBlank()) repo.observeVideos() else repo.searchVideos(q))
                    .map { LibraryVideos(it, q) }
            }
            // Cached whole, so the seed below keeps the query that actually produced this list
            // rather than whatever it reads as at recreation time.
            .onEach { filters.lastVideos.value = it }
            .stateIn(viewModelScope, WhileUiSubscribed, filters.lastVideos.value)

    fun onQueryChange(value: String) {
        _query.value = value
    }
}
