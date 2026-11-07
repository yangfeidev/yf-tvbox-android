package me.bsdnme.tvbox.ui.category

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import me.bsdnme.tvbox.ui.component.AdaptiveRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.core.theme.LocalFormFactor
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.data.model.Category
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.FamilyContent
import me.bsdnme.tvbox.ui.component.EmptyState
import me.bsdnme.tvbox.ui.component.PillChip
import me.bsdnme.tvbox.ui.component.PosterCard
import me.bsdnme.tvbox.ui.home.HeaderAction
import me.bsdnme.tvbox.vm.AppUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lives above the navigation tabs so returning to this screen retains loaded pages. */
class CategoryCache {
    private val sites = LinkedHashMap<Site, CategorySiteState>(4, 0.75f, true)
    fun forSite(site: Site): CategorySiteState {
        val result = sites.getOrPut(site) { CategorySiteState() }
        while (sites.size > 3) sites.remove(sites.keys.first())
        return result
    }
}

class CategorySiteState {
    var categories by mutableStateOf<List<Category>>(emptyList())
    var categoriesLoaded by mutableStateOf(false)
    var categoriesError by mutableStateOf<String?>(null)
    var selected by mutableStateOf("")
    private val pages = LinkedHashMap<String, CategoryPageState>(16, 0.75f, true)
    fun page(id: String): CategoryPageState {
        val result = pages.getOrPut(id) { CategoryPageState() }
        while (pages.size > 12) pages.remove(pages.keys.first())
        return result
    }
}

class CategoryPageState {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
    var page by mutableIntStateOf(1)
    var pageCount by mutableIntStateOf(1)
    var useChildren by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var moreError by mutableStateOf<String?>(null)
    var reload by mutableIntStateOf(0)
    var handledReload by mutableIntStateOf(0)
    var moreRetry by mutableIntStateOf(0)
    val grid = LazyGridState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(state: AppUiState, familyMode: Boolean, onOpenDetail: (Site, MediaItem) -> Unit, onOpenSearch: () -> Unit, cache: CategoryCache) {
    val form = LocalFormFactor.current
    val site = state.currentSite
    if (site == null) {
        EmptyState(icon = Icons.Outlined.Inbox, title = "暂无可用源站", description = "请先在设置里添加订阅")
        return
    }
    val siteState = remember(site, cache) { cache.forSite(site) }
    var categoriesRetry by remember(site) { mutableIntStateOf(0) }
    LaunchedEffect(site, categoriesRetry) {
        if (!siteState.categoriesLoaded || categoriesRetry > 0) {
            siteState.categoriesError = null
            try {
                siteState.categories = withContext(Dispatchers.IO) { FamilyContent.categories(site, familyMode) }.distinctBy { it.typeId }
                siteState.categoriesLoaded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { siteState.categoriesError = e.message ?: "分类加载失败" }
        }
    }
    val ids = listOf("") + siteState.categories.map { it.typeId }
    val initialPage = ids.indexOf(siteState.selected).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { ids.size })
    val scope = rememberCoroutineScope()
    val tabs = rememberLazyListState()
    LaunchedEffect(siteState.selected, ids) {
        val index = ids.indexOf(siteState.selected)
        if (form != FormFactor.Television && index >= 0) tabs.animateScrollToItem(index)
    }
    // Follow swipes after they settle; clicks and TV focus request a page directly.
    LaunchedEffect(pager, ids, form) {
        if (form == FormFactor.Television) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            siteState.selected = ids.getOrElse(index) { "" }
        }
    }
    fun select(index: Int) {
        if (index in ids.indices) {
            siteState.selected = ids[index]
            if (form != FormFactor.Television) scope.launch { pager.scrollToPage(index) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = form.horizontalPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("分类", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                HeaderAction(Icons.Outlined.Refresh, {
                    siteState.page(siteState.selected).reload++
                    if (!siteState.categoriesLoaded) categoriesRetry++
                }, contentDescription = "刷新")
                Spacer(Modifier.width(8.dp))
                HeaderAction(Icons.Outlined.Search, onOpenSearch, contentDescription = "搜索")
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(state = tabs, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                item { PillChip("全部", siteState.selected.isEmpty(), onClick = { select(0) }, modifier = Modifier.onFocusChanged { if (form == FormFactor.Television && it.isFocused) select(0) }) }
                items(siteState.categories, key = { it.typeId }) { category ->
                    val index = ids.indexOf(category.typeId)
                    PillChip(category.name, siteState.selected == category.typeId, onClick = { select(index) }, modifier = Modifier.onFocusChanged { if (form == FormFactor.Television && it.isFocused) select(index) })
                }
            }
            if (siteState.categoriesError != null) Text("分类加载失败，刷新重试", style = MaterialTheme.typography.bodySmall, color = XColors.TextSecondary)
        }
        if (form == FormFactor.Television) {
            val id = siteState.selected
            val pageState = remember(siteState, id) { siteState.page(id) }
            Box(Modifier.weight(1f)) {
                CategoryPage(site, id, siteState.categories, pageState, familyMode, active = true, onOpenDetail = onOpenDetail,
                    onRetryCategories = { if (!siteState.categoriesLoaded) categoriesRetry++ })
            }
        } else HorizontalPager(state = pager, modifier = Modifier.weight(1f), key = { ids[it] }) { index ->
            val id = ids[index]
            val pageState = remember(siteState, id) { siteState.page(id) }
            CategoryPage(site, id, siteState.categories, pageState, familyMode, active = pager.settledPage == index, onOpenDetail = onOpenDetail,
                onRetryCategories = { if (!siteState.categoriesLoaded) categoriesRetry++ })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPage(site: Site, id: String, categories: List<Category>, data: CategoryPageState, familyMode: Boolean, active: Boolean, onOpenDetail: (Site, MediaItem) -> Unit, onRetryCategories: () -> Unit) {
    val form = LocalFormFactor.current
    LaunchedEffect(site, id, data.reload, active, categories) {
        if (!active || (data.loaded && data.reload == data.handledReload)) return@LaunchedEffect
        val requestedReload = data.reload
        val isRefresh = requestedReload != data.handledReload
        var completed = false
        data.loading = true
        data.error = null
        data.moreError = null
        try {
            if (form == FormFactor.Television && id.isNotBlank() && !data.loaded) delay(250)
            val (result, fallback) = withContext(Dispatchers.IO) {
                val direct = FamilyContent.list(site, id, 1, familyMode)
                if (id.isNotBlank() && categories.any { it.parentId == id }) {
                    val children = FamilyContent.listChildren(site, id, categories, 1, familyMode)
                    direct.copy(items = (direct.items + children.items).distinctBy { it.id }, pageCount = maxOf(direct.pageCount, children.pageCount)) to true
                } else direct to false
            }
            data.useChildren = fallback
            data.items = result.items.distinctBy { it.id }
            data.page = result.page.coerceAtLeast(1)
            data.pageCount = result.pageCount
            data.loaded = true
            if (isRefresh) data.grid.scrollToItem(0)
            completed = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { data.error = e.message ?: "加载失败"; completed = true }
        finally {
            data.loading = false
            if (completed) data.handledReload = requestedReload
        }
    }
    LaunchedEffect(site, id, active, categories, data.moreRetry, data.reload) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val last = data.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            data.loaded && !data.loading && !data.loadingMore && data.moreError == null && data.page < data.pageCount && (data.items.isEmpty() || last >= data.items.size - 3)
        }.distinctUntilChanged().collect { shouldLoad ->
            if (!shouldLoad) return@collect
            data.loadingMore = true
            try {
                val next = data.page + 1
                val result = withContext(Dispatchers.IO) {
                    val direct = FamilyContent.list(site, id, next, familyMode)
                    if (data.useChildren) {
                        val children = FamilyContent.listChildren(site, id, categories, next, familyMode)
                        direct.copy(items = (direct.items + children.items).distinctBy { it.id }, page = next, pageCount = maxOf(direct.pageCount, children.pageCount))
                    } else direct
                }
                if (result.page < next) data.pageCount = data.page
                else {
                    data.items = (data.items + result.items).distinctBy { it.id }
                    data.page = result.page
                    data.pageCount = result.pageCount
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { data.moreError = e.message ?: "加载失败" }
            finally { data.loadingMore = false }
        }
    }
    AdaptiveRefreshBox(isRefreshing = data.loading, onRefresh = { data.reload++; onRetryCategories() }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Fixed(form.gridColumns), state = data.grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = form.horizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (data.loading && data.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = XColors.Accent, strokeWidth = 2.dp) }
                }
            } else if (data.error != null && data.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.Inbox, "加载失败", data.error, "重试", { data.reload++ }, Modifier.height(280.dp)) }
            } else if (data.items.isEmpty() && data.loaded) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.Inbox, "暂无内容", "换个分类或下拉刷新试试", "刷新", { data.reload++ }, Modifier.height(280.dp)) }
            } else {
                if (data.error != null) item(span = { GridItemSpan(maxLineSpan) }) { PillChip("刷新失败，点击重试", false, onClick = { data.reload++ }) }
                itemsIndexed(data.items, key = { _, item -> item.id }) { _, item -> PosterCard(item = item, onClick = { onOpenDetail(site, item) }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        when {
                            data.loadingMore -> CircularProgressIndicator(Modifier.size(24.dp), color = XColors.Accent, strokeWidth = 2.dp)
                            data.moreError != null -> PillChip("加载失败，点击重试", false, onClick = { data.moreError = null; data.moreRetry++ })
                            data.loaded && data.page >= data.pageCount -> Text("没有更多了", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
