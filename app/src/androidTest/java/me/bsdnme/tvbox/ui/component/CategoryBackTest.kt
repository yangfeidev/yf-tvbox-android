package me.bsdnme.tvbox.ui.component

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.data.model.Category
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.ui.category.CategoryCache
import me.bsdnme.tvbox.ui.category.CategoryScreen
import me.bsdnme.tvbox.vm.AppUiState
import org.junit.Rule
import org.junit.Test

class CategoryBackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun backFromGridReturnsToSelectedCategoryBeforeNavigation() {
        val site = Site("test", "test", "https://example.com", 1, true, true)
        val cache = CategoryCache()
        val siteState = cache.forSite(site).apply {
            categories = listOf(Category("movie", "电影"))
            categoriesLoaded = true
            selected = "movie"
        }
        val page = siteState.page("movie").apply {
            items = (0 until 36).map { MediaItem("$it", "影片$it") }
            loaded = true
        }
        var back: (() -> Unit)? = null
        var navigationReturns = 0
        rule.setContent {
            FeigeMediaTheme(FormFactor.Television) {
                val dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                SideEffect { back = { dispatcher.onBackPressed() } }
                CategoryScreen(AppUiState(currentSite = site), true, { _, _ -> }, {}, cache,
                    onReturnNavigation = { navigationReturns++ })
            }
        }
        rule.onNodeWithText("影片0").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.runOnIdle { back!!() }
        rule.waitForIdle()
        rule.onNodeWithText("电影").assertIsFocused()
        rule.runOnIdle {
            check(navigationReturns == 0)
            check(page.grid.firstVisibleItemIndex == 0)
            back!!()
        }
        rule.runOnIdle { check(navigationReturns == 1) }
    }
}
