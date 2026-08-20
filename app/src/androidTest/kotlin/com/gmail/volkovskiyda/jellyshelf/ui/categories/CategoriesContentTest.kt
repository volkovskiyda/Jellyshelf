package com.gmail.volkovskiyda.jellyshelf.ui.categories

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_CHANNEL
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_AUTO_YEAR
import com.gmail.volkovskiyda.jellyshelf.domain.model.CATEGORY_TYPE_OTHERS
import com.gmail.volkovskiyda.jellyshelf.domain.model.Category
import com.gmail.volkovskiyda.jellyshelf.domain.model.CategoryWithCount
import com.gmail.volkovskiyda.jellyshelf.ui.FakeScrollPositionRepository
import com.gmail.volkovskiyda.jellyshelf.ui.theme.JellyshelfTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The categories screen's tab construction and its two search modes.
 *
 * The interesting rules are structural rather than cosmetic: a dimension with no categories gets
 * no tab, the virtual "Others" tab is browse-only and disappears while searching, and searching
 * across all dimensions abandons tabs entirely for one flat list. None of that is visible from
 * rendering a single populated state.
 *
 * Instrumented, per this project's no-Robolectric convention;
 * `connectedDebugAndroidTest` skips itself when no device is attached.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Fails this class's tests on unlabelled clickables, undersized touch targets and unreadable
     * contrast — checked before every action that changes the UI, so the whole rendered tree is
     * covered, not only the nodes an assertion happens to name.
     */
    @Before
    fun enableAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
    }

    private fun category(id: String, name: String, type: String, count: Int = 3) =
        CategoryWithCount(
            category = Category(id = id, name = name, type = type, createdAt = 0L),
            videoCount = count,
        )

    private val channels = listOf(
        category("ch-1", "Sample Channel", CATEGORY_TYPE_AUTO_CHANNEL),
        category("ch-2", "Another Channel", CATEGORY_TYPE_AUTO_CHANNEL),
    )
    private val years = listOf(category("yr-2026", "2026", CATEGORY_TYPE_AUTO_YEAR))
    private val others = listOf(category("others-watched", "Watched", CATEGORY_TYPE_OTHERS))

    @Suppress("LongParameterList") // mirrors the composable under test
    private fun setContent(
        categories: CategoryList?,
        others: List<CategoryWithCount> = emptyList(),
        query: String = "",
        searchAll: Boolean = false,
        selectedType: String? = CATEGORY_TYPE_AUTO_CHANNEL,
        onCategoryClick: (String, String) -> Unit = { _, _ -> },
        onSearchAllChange: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            JellyshelfTheme(dynamicColor = false) {
                CategoriesContent(
                    categoriesOrNull = categories,
                    others = others,
                    query = query,
                    searchAll = searchAll,
                    selectedType = selectedType,
                    selectionLoaded = true,
                    onQueryChange = {},
                    onSearchAllChange = onSearchAllChange,
                    onSelectedTypeChange = {},
                    onCategoryClick = onCategoryClick,
                    scrollStore = FakeScrollPositionRepository(),
                )
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    @Test
    fun aDimensionWithCategories_getsATab_andOneWithoutDoesNot() {
        setContent(CategoryList(channels, pristine = true))

        composeRule.onNodeWithText(string(R.string.dim_channels)).assertIsDisplayed()
        // No year categories were supplied, so that dimension has no tab at all.
        composeRule.onNodeWithText(string(R.string.dim_years)).assertDoesNotExist()
    }

    @Test
    fun theSelectedTabsCategories_areListed() {
        setContent(CategoryList(channels + years, pristine = true))

        composeRule.onNodeWithText("Sample Channel").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dim_years)).assertIsDisplayed()
    }

    @Test
    fun tappingACategory_reportsItsIdAndTitle() {
        var clicked: Pair<String, String>? = null
        setContent(
            CategoryList(channels, pristine = true),
            onCategoryClick = { id, title -> clicked = id to title },
        )

        composeRule.onNodeWithText("Sample Channel").performClick()

        assertEquals("ch-1" to "Sample Channel", clicked)
    }

    @Test
    fun theOthersTab_showsWhileBrowsing() {
        setContent(CategoryList(channels, pristine = true), others = others)

        composeRule.onNodeWithText(string(R.string.dim_others)).assertIsDisplayed()
    }

    @Test
    fun theOthersTab_isLeftOutOfSearch() {
        // Others holds virtual watch/uncategorized filters, which are browse-only — search's scope
        // is the stored, name-searchable categories.
        setContent(CategoryList(channels, pristine = false), others = others, query = "chan")

        composeRule.onNodeWithText(string(R.string.dim_others)).assertDoesNotExist()
    }

    @Test
    fun browsing_offersNoSearchAllToggle() {
        setContent(CategoryList(channels, pristine = true))

        composeRule.onNodeWithText(string(R.string.search_all_categories)).assertDoesNotExist()
    }

    @Test
    fun searching_offersTheSearchAllToggle() {
        setContent(CategoryList(channels, pristine = false), query = "chan")

        composeRule.onNodeWithText(string(R.string.search_all_categories)).assertIsDisplayed()
    }

    @Test
    fun searchingEveryDimension_dropsTheTabsForOneFlatList() {
        // A query that matches neither category name, so the assertions below cannot accidentally
        // be satisfied by the search field's own text.
        setContent(
            CategoryList(channels + years, pristine = false),
            query = "zzz",
            searchAll = true,
        )

        // Results from both dimensions, and no tab row to have picked one of them.
        composeRule.onNodeWithText("Sample Channel").assertIsDisplayed()
        composeRule.onNodeWithText("2026").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dim_channels)).assertDoesNotExist()
    }

    @Test
    fun aCrossDimensionSearchThatMatchedNothing_namesTheQuery() {
        setContent(CategoryList(emptyList(), pristine = false), query = "nothing", searchAll = true)

        composeRule.onNodeWithText(string(R.string.no_categories_match, "nothing")).assertIsDisplayed()
    }

    @Test
    fun aPendingFirstEmission_showsNeitherListNorEmptyGuidance() {
        setContent(categories = null)

        composeRule.onNodeWithText(string(R.string.empty_categories)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.dim_channels)).assertDoesNotExist()
    }
}
