package com.yangfei.xingmu.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.data.model.Category
import com.yangfei.xingmu.data.model.LibraryEntry
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.model.Site
import com.yangfei.xingmu.data.model.toMediaItem
import com.yangfei.xingmu.data.net.VodApi
import com.yangfei.xingmu.ui.component.BrandMark
import com.yangfei.xingmu.ui.component.EmptyState
import com.yangfei.xingmu.ui.component.FocusableSurface
import com.yangfei.xingmu.ui.component.GridSkeleton
import com.yangfei.xingmu.ui.component.HeroBanner
import com.yangfei.xingmu.ui.component.MediaRail
import com.yangfei.xingmu.vm.AppUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 首页。
 *
 * 数据来源：当前源站的「最新」列表 + 分类列表 + 本地历史。
 * 结构：
 * - 顶部：源站名 + 搜索 / 设置入口
 * - [HeroBanner]：取最新列表首条做大图
 * - 继续观看轨道：来自本地历史，不发请求
 * - 分类轨道：取分类表前 4 个，各拉一页，任一失败不影响其他轨道
 *
 * 所有数据按 `site.key` 做 key：切换源站时 LaunchedEffect 自动重跑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppUiState,
    onOpenDetail: (Site, MediaItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val formFactor = LocalFormFactor.current
    val site = state.currentSite

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var rails by remember { mutableStateOf<List<Pair<Category, List<MediaItem>>>>(emptyList()) }
    var loadGeneration by remember { mutableIntStateOf(0) }

    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(site, reloadKey) {
        val current = site ?: return@LaunchedEffect
        val generation = ++loadGeneration
        loading = true
        error = null
        latest = emptyList()
        rails = emptyList()
        try {
            val page = withContext(Dispatchers.IO) { VodApi.list(current) }
            latest = page.items
            val categories = withContext(Dispatchers.IO) {
                runCatching { VodApi.categories(current) }.getOrDefault(emptyList())
            }.ifEmpty { page.categories }
            rails = coroutineScope {
                categories.take(4).map { category ->
                    async(Dispatchers.IO) {
                        category to runCatching {
                            VodApi.list(current, typeId = category.typeId).items
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().filter { it.second.isNotEmpty() }
            }
            if (latest.isEmpty()) latest = rails.firstOrNull()?.second.orEmpty()
            if (latest.isEmpty()) error = "该源没有返回内容，换一个源试试"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            if (generation == loadGeneration) loading = false
        }
    }

    if (site == null) {
        EmptyState(
            icon = Icons.Outlined.Search,
            title = "还没有可用的订阅",
            description = "添加 TVBox 订阅地址后，这里会显示该源的影视内容",
            actionLabel = "去添加订阅",
            onAction = onOpenSettings,
        )
        return
    }

    PullToRefreshBox(indicator = {}, isRefreshing = loading, onRefresh = { reloadKey++ }, modifier = Modifier.fillMaxSize()) {
    if (loading && latest.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(site, onOpenSearch, onOpenSettings) { reloadKey++ }
            GridSkeleton(
                modifier = Modifier.weight(1f),
                rows = 3,
                contentPadding = PaddingValues(
                    horizontal = formFactor.horizontalPadding,
                    vertical = 16.dp,
                ),
            )
        }
        return@PullToRefreshBox
    }

    if (error != null && latest.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(site, onOpenSearch, onOpenSettings) { reloadKey++ }
            EmptyState(
                icon = Icons.Outlined.Tune,
                title = "内容加载失败",
                description = "$error\n\n这个源响应慢或已失效，到设置里换一个源通常就好了",
                actionLabel = "重试",
                onAction = { reloadKey++ },
                modifier = Modifier.weight(1f),
            )
        }
        return@PullToRefreshBox
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { HomeHeader(site, onOpenSearch, onOpenSettings) { reloadKey++ } }
        if (latest.isNotEmpty()) {
            item {
                HeroBanner(
                    item = latest.first(),
                    tagline = "最新上线",
                    onPlay = { onOpenDetail(site, latest.first()) },
                    onDetail = { onOpenDetail(site, latest.first()) },
                )
            }
        }
        val history = state.history.filter { entry -> state.usableSites.any { it.key == entry.siteKey } }.take(10)
        if (history.isNotEmpty()) {
            item {
                MediaRail(
                    title = "继续观看",
                    items = history.map { it.toMediaItem() },
                    onItemClick = { item ->
                        val entry = history.firstOrNull { it.mediaId == item.id && it.name == item.name }
                        val originalSite = state.usableSites.firstOrNull { it.key == entry?.siteKey }
                        if (originalSite != null) onOpenDetail(originalSite, item)
                    },
                )
            }
        }
        item {
            MediaRail(
                title = "最近更新",
                items = latest,
                onItemClick = { item -> onOpenDetail(site, item) },
            )
        }
        items(rails.size) { index ->
            val (category, items) = rails[index]
            MediaRail(
                title = category.name,
                items = items,
                onItemClick = { item -> onOpenDetail(site, item) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    }
}

@Composable
private fun HomeHeader(site: Site, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    val padding = LocalFormFactor.current.horizontalPadding
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = padding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BrandMark(size = 34.dp)
            Spacer(Modifier.height(6.dp))
            Text("当前片源 · ${site.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        HeaderAction(Icons.Outlined.Refresh, onRefresh)
        Spacer(Modifier.size(8.dp))
        HeaderAction(Icons.Outlined.Search, onOpenSearch)
        Spacer(Modifier.size(8.dp))
        HeaderAction(Icons.Outlined.Tune, onOpenSettings)
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(XRadii.lg),
        focusScale = 1.06f,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(XColors.Surface, RoundedCornerShape(XRadii.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = XColors.TextSecondary, modifier = Modifier.size(19.dp))
        }
    }
}
