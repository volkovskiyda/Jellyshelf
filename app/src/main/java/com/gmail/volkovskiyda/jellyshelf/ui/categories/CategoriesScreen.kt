package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmail.volkovskiyda.jellyshelf.data.local.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.ui.EmptyState
import com.gmail.volkovskiyda.jellyshelf.ui.rememberContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onCategoryClick: (categoryId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = rememberContainer()
    val categories by remember { container.libraryRepository.observeCategories() }
        .collectAsStateWithLifecycle(emptyList())

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Categories") })
        if (categories.isEmpty()) {
            EmptyState("No categories yet. Sync to auto-group videos by channel.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(categories, key = { it.category.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategoryClick(item.category.id, item.category.name) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(item.category.name, style = MaterialTheme.typography.bodyLarge)
                        val kind = if (item.category.type == CATEGORY_TYPE_AUTO_CHANNEL) "Channel" else "Manual"
                        Text(
                            "$kind  •  ${item.videoCount} video${if (item.videoCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
