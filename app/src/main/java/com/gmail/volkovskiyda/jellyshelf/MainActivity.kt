package com.gmail.volkovskiyda.jellyshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gmail.volkovskiyda.jellyshelf.navigation.AppNavKey
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoriesScreen
import com.gmail.volkovskiyda.jellyshelf.ui.categories.CategoryVideosScreen
import com.gmail.volkovskiyda.jellyshelf.ui.MainViewModel
import com.gmail.volkovskiyda.jellyshelf.ui.detail.DetailScreen
import com.gmail.volkovskiyda.jellyshelf.ui.library.LibraryScreen
import com.gmail.volkovskiyda.jellyshelf.ui.settings.SettingsScreen
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme

private data class TopLevel(val key: AppNavKey, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JellyshelfTheme {
                JellyshelfApp()
            }
        }
    }
}

@Composable
private fun JellyshelfApp(viewModel: MainViewModel = viewModel()) {
    val startKey by viewModel.startKey.collectAsStateWithLifecycle()

    // Render nothing until the start destination is resolved, so Library never flashes first.
    startKey?.let { JellyshelfNav(it) }
}

@Composable
private fun JellyshelfNav(startKey: AppNavKey) {
    val backStack = rememberNavBackStack(startKey)
    val saveableStateHolderDecorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()

    val topLevel = listOf(
        TopLevel(AppNavKey.Library, "Library", Icons.Filled.VideoLibrary),
        TopLevel(AppNavKey.Categories, "Categories", Icons.AutoMirrored.Filled.ViewList),
        TopLevel(AppNavKey.Settings, "Settings", Icons.Filled.Settings),
    )
    val current = backStack.lastOrNull()
    val showBottomBar = topLevel.any { it.key == current }

    fun switchTo(key: AppNavKey) {
        if (backStack.lastOrNull() == key) return
        backStack.clear()
        backStack.add(key)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = current == item.key,
                            onClick = { switchTo(item.key) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavDisplay(
            backStack = backStack,
            // consumeWindowInsets keeps each screen's own TopAppBar from applying the status-bar
            // inset a second time on top of the scaffold padding.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            entryDecorators = listOf(saveableStateHolderDecorator, viewModelStoreDecorator),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        ) { key ->
            when (key) {
                is AppNavKey.Library -> NavEntry(key) {
                    LibraryScreen(onVideoClick = { backStack.add(AppNavKey.Detail(it.youtubeId)) })
                }

                is AppNavKey.Categories -> NavEntry(key) {
                    CategoriesScreen(onCategoryClick = { id, title ->
                        backStack.add(AppNavKey.CategoryVideos(id, title))
                    })
                }

                is AppNavKey.Settings -> NavEntry(key) {
                    SettingsScreen()
                }

                is AppNavKey.CategoryVideos -> NavEntry(key) {
                    CategoryVideosScreen(
                        categoryId = key.categoryId,
                        title = key.title,
                        onVideoClick = { backStack.add(AppNavKey.Detail(it.youtubeId)) },
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                    )
                }

                is AppNavKey.Detail -> NavEntry(key) {
                    DetailScreen(
                        youtubeId = key.youtubeId,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                    )
                }

                else -> throw IllegalArgumentException("Unknown key: $key")
            }
        }
    }
}
