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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_DURATION
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_MONTH
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_YT_CATEGORY
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_MANUAL
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.data.local.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.LoadingState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberPersistedLazyListState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onCategoryClick: (categoryId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = viewModel(),
) {
    val categoriesOrNull by viewModel.categories.collectAsStateWithLifecycle()
    val others by viewModel.others.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchAll by viewModel.searchAll.collectAsStateWithLifecycle()
    val categories = categoriesOrNull.orEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_categories)) })
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.search_categories)) },
        )

        val searching = query.isNotBlank()
        if (searching) {
            SearchAllToggle(checked = searchAll, onCheckedChange = viewModel::onSearchAllChange)
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
                // Cross-dimension search: one flat, matches-first list (see searchWithCounts), each
                // row tagged with its kind since there are no tabs to convey it.
                LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
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
                persistScroll = !searching,
                onCategoryClick = onCategoryClick,
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
    persistScroll: Boolean,
    onCategoryClick: (categoryId: String, title: String) -> Unit,
) {
    if (tabs.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val selected = pagerState.currentPage.coerceAtMost(tabs.lastIndex)

    ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
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
        val listState = if (persistScroll) {
            rememberPersistedLazyListState("categories.${tab.type}")
        } else {
            rememberLazyListState()
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
        if (items.isEmpty()) null
        else CategoryTab(type, titleRes, if (searching) items else naturalSort(type, items))
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
