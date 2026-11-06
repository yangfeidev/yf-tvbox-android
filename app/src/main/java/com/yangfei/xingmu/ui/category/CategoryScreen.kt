package com.yangfei.xingmu.ui.category

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.yangfei.xingmu.core.theme.FormFactor
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.data.model.Category
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.model.Site
import com.yangfei.xingmu.data.net.VodApi
import com.yangfei.xingmu.ui.component.EmptyState
import com.yangfei.xingmu.ui.component.PillChip
import com.yangfei.xingmu.ui.component.PosterCard
import com.yangfei.xingmu.vm.AppUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(state: AppUiState, onOpenDetail: (Site, MediaItem) -> Unit) {
    val form = LocalFormFactor.current
    val site = state.currentSite
    var categories by remember(site) { mutableStateOf<List<Category>>(emptyList()) }
    var selected by remember(site) { mutableStateOf("") }
    var items by remember(site) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember(site) { mutableIntStateOf(1) }
    var pageCount by remember(site) { mutableIntStateOf(1) }
    var useChildren by remember(site) { mutableStateOf(false) }
    var loading by remember(site) { mutableStateOf(true) }
    var loadingMore by remember(site) { mutableStateOf(false) }
    var error by remember(site) { mutableStateOf<String?>(null) }
    var categoriesError by remember(site) { mutableStateOf<String?>(null) }
    var moreError by remember(site) { mutableStateOf<String?>(null) }
    var reload by remember(site) { mutableIntStateOf(0) }
    var moreRetry by remember(site) { mutableIntStateOf(0) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()

    LaunchedEffect(site, reload) {
        val current = site ?: return@LaunchedEffect
        categoriesError = null
        try {
            categories = withContext(Dispatchers.IO) { VodApi.categories(current) }.distinctBy { it.typeId }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { categoriesError = e.message ?: "分类加载失败" }
    }
    LaunchedEffect(site, selected, reload) {
        val current = site ?: return@LaunchedEffect
        val generation = ++loadGeneration
        loading = true
        error = null
        moreError = null
        items = emptyList()
        page = 1
        pageCount = 1
        useChildren = false
        grid.scrollToItem(0)
        try {
            val (result, fallback) = withContext(Dispatchers.IO) {
                val direct = VodApi.list(current, selected, 1)
                if (selected.isNotBlank() && categories.any { it.parentId == selected }) {
                    val children = VodApi.listChildren(current, selected, categories, 1)
                    direct.copy(items = (direct.items + children.items).distinctBy { it.id },
                        pageCount = maxOf(direct.pageCount, children.pageCount)) to true
                } else direct to false
            }
            useChildren = fallback
            items = result.items
            page = result.page.coerceAtLeast(1)
            pageCount = result.pageCount
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "加载失败" }
        finally { if (generation == loadGeneration) loading = false }
    }
    LaunchedEffect(site, selected, reload, moreRetry) {
        val current = site ?: return@LaunchedEffect
        snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            !loading && !loadingMore && moreError == null && items.isNotEmpty() && page < pageCount && last >= items.size - 3
        }.distinctUntilChanged().collect { shouldLoad ->
            if (!shouldLoad) return@collect
            loadingMore = true
            try {
                val next = page + 1
                val result = withContext(Dispatchers.IO) {
                    val direct = VodApi.list(current, selected, next)
                    if (useChildren) {
                        val children = VodApi.listChildren(current, selected, categories, next)
                        direct.copy(items = (direct.items + children.items).distinctBy { it.id },
                            page = next, pageCount = maxOf(direct.pageCount, children.pageCount))
                    } else direct
                }
                if (result.items.isEmpty() || result.page < next) {
                    pageCount = page
                } else {
                    items = (items + result.items).distinctBy { it.id }
                    page = result.page
                    pageCount = result.pageCount
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { moreError = e.message ?: "加载失败" }
            finally { loadingMore = false }
        }
    }
    if (site == null) {
        EmptyState(icon = Icons.Outlined.Inbox, title = "暂无可用源站", description = "请先在设置里添加订阅")
        return
    }
    PullToRefreshBox(indicator = {}, isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(form.gridColumns), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = form.horizontalPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("分类", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        PillChip("刷新", false, onClick = { reload++ })
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        item { PillChip("全部", selected.isEmpty(), onClick = { selected = "" }, modifier = Modifier.onFocusChanged { if (form == FormFactor.Television && it.isFocused) selected = "" }) }
                        items(categories, key = { it.typeId }) { category ->
                            PillChip(category.name, selected == category.typeId, onClick = { selected = category.typeId }, modifier = Modifier.onFocusChanged { if (form == FormFactor.Television && it.isFocused) selected = category.typeId })
                        }
                    }
                    if (categoriesError != null) {
                        Text("分类加载失败，下拉刷新重试", style = MaterialTheme.typography.bodySmall, color = XColors.TextSecondary)
                    }
                }
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = XColors.Accent, strokeWidth = 2.dp)
                    }
                }
            } else if (error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Outlined.Inbox, "加载失败", error, "重试", { reload++ }, Modifier.height(280.dp))
                }
            } else if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Outlined.Inbox, "暂无内容", "换个分类或下拉刷新试试", "刷新", { reload++ }, Modifier.height(280.dp))
                }
            } else {
                itemsIndexed(items) { _, item -> PosterCard(item = item, onClick = { onOpenDetail(site, item) }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        when {
                            loadingMore -> CircularProgressIndicator(Modifier.size(24.dp), color = XColors.Accent, strokeWidth = 2.dp)
                            moreError != null -> PillChip("加载失败，点击重试", false, onClick = { moreError = null; moreRetry++ })
                            page >= pageCount -> Text("没有更多了", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
