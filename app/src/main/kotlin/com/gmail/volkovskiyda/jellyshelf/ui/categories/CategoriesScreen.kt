package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.SearchField
import com.gmail.volkovskiyda.jellyshelf.ui.rememberPersistedLazyListState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Handles for the baseline-profile generator and `JourneyBenchmark`, which drive this screen through
 * UiAutomator and so cannot match on Compose semantics the way the instrumented tests do. Named in
 * resource-id style because that is what they become: MainActivity's root Scaffold sets
 * `testTagsAsResourceId`, which republishes every tag below it as an Android resource id. They have
 * no other meaning — nothing in the app or the test suite reads them.
 *
 * A row rather than the list around it: a list exists before it has anything in it, and what a
 * journey has to wait for is content.
 */
internal const val CATEGORY_ROW_TAG = "category_row"
internal const val CATEGORY_SEARCH_TAG = "category_search"

/**
 * Categories tab: binds [CategoriesViewModel] to the stateless [CategoriesContent] below, which
 * previews and tests can render without a ViewModel or a Koin container.
 */
@Composable
fun CategoriesScreen(
    onCategoryClick: (categoryId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = koinViewModel(),
) {
    val categoriesOrNull by viewModel.categories.collectAsStateWithLifecycle()
    val others by viewModel.others.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchAll by viewModel.searchAll.collectAsStateWithLifecycle()
    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    val selectionLoaded by viewModel.selectionLoaded.collectAsStateWithLifecycle()

    CategoriesContent(
        categoriesOrNull = categoriesOrNull,
        others = others,
        query = query,
        searchAll = searchAll,
        selectedType = selectedType,
        selectionLoaded = selectionLoaded,
        onQueryChange = viewModel::onQueryChange,
        onSearchAllChange = viewModel::onSearchAllChange,
        onSelectedTypeChange = viewModel::onSelectedTypeChange,
        onCategoryClick = onCategoryClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesContent(
    categoriesOrNull: CategoryList?,
    others: List<CategoryWithCount>,
    query: String,
    searchAll: Boolean,
    selectedType: String?,
    selectionLoaded: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchAllChange: (Boolean) -> Unit,
    onSelectedTypeChange: (String) -> Unit,
    onCategoryClick: (categoryId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    // Injected by default; host-side rendering passes an in-memory stand-in.
    scrollStore: ScrollPositionRepository = koinInject(),
) {
    val categories = categoriesOrNull?.items.orEmpty()
    // Tagged on the emission, not derived from [query]: the query blanks a frame before the
    // unfiltered list re-emits, so a tab's scroll must stay transient until the real list is back.
    val listPristine = categoriesOrNull?.pristine == true

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_categories)) })
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.search_categories),
            modifier = Modifier.testTag(CATEGORY_SEARCH_TAG),
        )

        val searching = query.isNotBlank()
        if (searching) {
            SearchAllToggle(checked = searchAll, onCheckedChange = onSearchAllChange)
        }

        // The "Others" tab (virtual watch/uncategorized filters) is browse-only; it is left out of
        // search, whose scope is the stored, name-searchable categories.
        val othersTitle = stringResource(R.string.dim_others)
        val tabs = remember(categories, others, searching, othersTitle) {
            tabsOf(categories, searching).let { stored ->
                if (!searching && others.isNotEmpty()) {
                    stored + CategoryTab(CATEGORY_TYPE_OTHERS, null, others, othersTitle)
                } else {
                    stored
                }
            }
        }

        if (categoriesOrNull == null) {
            // First Room emission still pending — don't flash the empty-state guidance.
            LoadingState()
        } else if (searching && searchAll) {
            if (categories.isEmpty()) {
                EmptyState(stringResource(R.string.no_categories_match, query))
            } else {
                // Cross-dimension search: one flat, most-relevant-first list (see searchWithCounts),
                // each row tagged with its kind since there are no tabs to convey it. Jump to the
                // top on every keystroke so the best matches are in view.
                val listState = rememberLazyListState()
                // Reset to the top on a new keystroke, not when returning from a category. Fire on
                // the new list (rows are keyed by id, so the list keeps the anchored row in view on
                // a content change; scrolling on the query a frame earlier gets undone by that key
                // preservation when removing a character broadens the results). The saveable query
                // guard skips the reset on a plain back-navigation, keeping the scrolled position.
                var lastQuery by rememberSaveable { mutableStateOf(query) }
                LaunchedEffect(categories) {
                    if (query != lastQuery) {
                        lastQuery = query
                        listState.scrollToItem(0)
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(categories, key = { it.category.id }) { item ->
                        CategoryRow(item = item, showType = true, onClick = onCategoryClick)
                        HorizontalDivider()
                    }
                }
            }
        } else if (tabs.isEmpty()) {
            val message = if (searching) {
                stringResource(R.string.no_categories_match, query)
            } else {
                stringResource(R.string.empty_categories)
            }
            EmptyState(message)
        } else {
            TabbedCategories(
                tabs = tabs,
                selectedType = selectedType,
                selectionLoaded = selectionLoaded,
                onSelectedTypeChange = onSelectedTypeChange,
                persistScroll = listPristine,
                searchKey = query,
                onCategoryClick = onCategoryClick,
                scrollStore = scrollStore,
            )
        }
    }
}

@Composable
private fun SearchAllToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        // toggleable merges the row and checkbox into one accessible checkbox target.
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(R.string.search_all_categories), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TabbedCategories(
    tabs: List<CategoryTab>,
    selectedType: String?,
    selectionLoaded: Boolean,
    onSelectedTypeChange: (String) -> Unit,
    persistScroll: Boolean,
    searchKey: String,
    onCategoryClick: (categoryId: String, title: String) -> Unit,
    scrollStore: ScrollPositionRepository,
) {
    if (tabs.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val selected = pagerState.currentPage.coerceAtMost(tabs.lastIndex)

    // Keyed on the dimensions themselves, not on the tabs list: every Room emission (a bulk
    // metadata fetch produces a stream of them) rebuilds `tabs` into a fresh list holding the
    // same dimensions, and restarting on that identity used to yank a mid-swipe user back —
    // the swipe had moved currentPage, but settledPage hadn't yet updated selectedType, so the
    // restarted effect "restored" the page the user was leaving. Equal type lists compare equal,
    // so the effect now restarts only when the tab set genuinely changes.
    val tabTypes = tabs.map { it.type }

    // Selection is anchored to the dimension, not the raw index: when the tab set changes (a
    // dimension appears after sync, "Others" loads in, search filters tabs out), the pager
    // follows the previously selected dimension to its new position, then resumes tracking
    // the settled page. Restore-before-track ordering keeps the tracker from recording the
    // shifted page a set change momentarily leaves under the old index. The selection itself
    // is container-owned (survives bottom-nav tab switches, which clear saveable state) and
    // seeded from disk on launch (survives process restart) — so wait for selectionLoaded to
    // apply the restored dimension before tracking, rather than committing the initial page 0.
    LaunchedEffect(tabTypes, selectionLoaded) {
        if (!selectionLoaded) return@LaunchedEffect
        val target = selectedType?.let { type -> tabTypes.indexOf(type) } ?: -1
        // A set change landing while the user is dragging is the one case where the restore and
        // the gesture disagree — the gesture wins, and the tracker records wherever it settles.
        if (target >= 0 && target != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(target)
        }
        // When the selected dimension has no tab in this set (a search filtered it out), the
        // pager sits on a fallback page the user never chose — skip that first emission so
        // clearing the search returns to the real selection; user swipes still track.
        val keepSelection = target < 0 && selectedType != null
        snapshotFlow { pagerState.settledPage }
            .drop(if (keepSelection) 1 else 0)
            .collect { page ->
                tabTypes.getOrNull(page)?.let { onSelectedTypeChange(it) }
            }
    }

    PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = selected == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(tab.title()) },
            )
        }
    }
    // Pages keyed by dimension so page state stays with its tab when the tab set changes
    // (a dimension appearing after sync, "Others" loading in, search filtering tabs out).
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { tabs[it].type },
    ) { page ->
        val tab = tabs[page]
        // Browsing persists (and restores) each dimension's scroll position. While searching the
        // per-tab lists are transient result sets: they start at the top and jump back on every
        // keystroke, so the best matches for the new query are in view.
        val listState = if (persistScroll) {
            rememberPersistedLazyListState("categories.${tab.type}", scrollStore)
        } else {
            rememberLazyListState()
        }
        if (!persistScroll) {
            // Reset to the top on a new query, not when re-entering composition after visiting a
            // category and pressing back. Fire on the new list (rows are keyed by id, so the list
            // keeps the anchored row in view on a content change; scrolling on the query a frame
            // earlier gets undone by that key preservation when removing a character broadens the
            // results). The saveable guard skips the reset on a plain back-navigation.
            var lastSearchKey by rememberSaveable { mutableStateOf(searchKey) }
            LaunchedEffect(tab.items) {
                if (searchKey != lastSearchKey) {
                    lastSearchKey = searchKey
                    listState.scrollToItem(0)
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(tab.items, key = { it.category.id }) { item ->
                CategoryRow(item = item, showType = false, onClick = onCategoryClick)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CategoryRow(
    item: CategoryWithCount,
    showType: Boolean,
    onClick: (categoryId: String, title: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(item.category.id, item.category.name) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(CATEGORY_ROW_TAG),
    ) {
        Text(item.category.name, style = MaterialTheme.typography.bodyLarge)
        val count = pluralStringResource(R.plurals.video_count, item.videoCount, item.videoCount)
        val subtitle = if (showType) {
            "${stringResource(categoryTypeLabelRes(item.category.type))}  •  $count"
        } else {
            count
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class CategoryTab(
    val type: String,
    val titleRes: Int?,
    val items: List<CategoryWithCount>,
    /** Pre-resolved title when the tab is built outside a composable resource lookup. */
    val resolvedTitle: String = "",
)

@Composable
private fun CategoryTab.title(): String = titleRes?.let { stringResource(it) } ?: resolvedTitle

/** Ordered tab titles, one per dimension. Only dimensions with at least one category get a tab. */
private val DIMENSIONS = listOf(
    CATEGORY_TYPE_AUTO_CHANNEL to R.string.dim_channels,
    CATEGORY_TYPE_AUTO_YT_CATEGORY to R.string.dim_youtube_categories,
    CATEGORY_TYPE_AUTO_YEAR to R.string.dim_years,
    CATEGORY_TYPE_AUTO_MONTH to R.string.dim_months,
    CATEGORY_TYPE_AUTO_DURATION to R.string.dim_durations,
    CATEGORY_TYPE_MANUAL to R.string.dim_manual,
)

/**
 * Split the flat category list into one tab per non-empty dimension. When browsing, each tab is
 * sorted naturally (channels/YouTube/manual alphabetically, years and months newest-first,
 * durations by band). When [searching], the incoming matches-first order is preserved so hits
 * float to the top of each tab.
 */
private fun tabsOf(all: List<CategoryWithCount>, searching: Boolean): List<CategoryTab> {
    val byType = all.groupBy { it.category.type }
    return DIMENSIONS.mapNotNull { (type, titleRes) ->
        val items = byType[type].orEmpty()
        if (items.isEmpty()) {
            null
        } else {
            CategoryTab(type, titleRes, if (searching) items else naturalSort(type, items))
        }
    }
}

private fun naturalSort(type: String, items: List<CategoryWithCount>): List<CategoryWithCount> = when (type) {
    CATEGORY_TYPE_AUTO_YEAR, CATEGORY_TYPE_AUTO_MONTH -> items.sortedByDescending { it.category.name }
    CATEGORY_TYPE_AUTO_DURATION -> items.sortedBy { it.category.id }
    else -> items.sortedBy { it.category.name.lowercase() }
}

private fun categoryTypeLabelRes(type: String): Int = when (type) {
    CATEGORY_TYPE_AUTO_CHANNEL -> R.string.type_channel
    CATEGORY_TYPE_AUTO_YT_CATEGORY -> R.string.type_youtube_category
    CATEGORY_TYPE_AUTO_YEAR -> R.string.type_year
    CATEGORY_TYPE_AUTO_MONTH -> R.string.type_month
    CATEGORY_TYPE_AUTO_DURATION -> R.string.type_duration
    CATEGORY_TYPE_OTHERS -> R.string.type_others
    else -> R.string.type_manual
}
