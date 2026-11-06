package com.yangfei.xingmu.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yangfei.xingmu.core.theme.FormFactor
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.core.theme.XSpacing
import com.yangfei.xingmu.data.model.Channel
import com.yangfei.xingmu.data.model.ChannelGroup
import com.yangfei.xingmu.data.model.LiveSourceEntry
import com.yangfei.xingmu.data.net.LiveApi
import com.yangfei.xingmu.ui.component.EmptyState
import com.yangfei.xingmu.ui.component.FocusableSurface
import com.yangfei.xingmu.ui.component.LoadingBox
import com.yangfei.xingmu.ui.component.PillChip
import com.yangfei.xingmu.vm.AppUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 直播页。
 *
 * 布局差异：
 * - 手机：顶部分组横向滚动，下方 2 列频道卡
 * - 平板 / 电视：左侧分组竖列表（常驻），右侧 4~5 列频道卡
 *
 * 源的选择顺序：设置里的自定义地址 → 订阅自带的 lives → 都没有则提示去设置。
 * 解析时会自动丢弃 rtp/udp/rtsp 组播地址，这些在公网设备上无法播放。
 */
@Composable
fun LiveScreen(
    state: AppUiState,
    vm: com.yangfei.xingmu.vm.AppViewModel,
    onPlayChannel: (Channel) -> Unit,
) {
    val formFactor = LocalFormFactor.current
    val padding = formFactor.horizontalPadding

    val sources = remember(state.settings.liveUrl, state.document) {
        buildList {
            state.settings.liveUrl.trim().takeIf { it.isNotEmpty() }?.let {
                add(LiveSourceEntry("自定义直播源", it))
            }
            addAll(state.document?.liveSources.orEmpty())
        }.distinctBy { it.url }
    }

    var sourceIndex by remember(sources.size) { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<ChannelGroup>>(emptyList()) }
    var groupIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val currentSource = sources.getOrNull(sourceIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)))

    LaunchedEffect(currentSource?.url, reloadKey) {
        val source = currentSource ?: return@LaunchedEffect
        loading = true
        error = null
        groups = emptyList()
        groupIndex = 0
        try {
            val parsed = vm.loadLive(source.url, forceRefresh = reloadKey > 0)
            groups = parsed
            if (parsed.isEmpty()) {
                error = "该直播源没有解析出可播放的频道"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 切换页面时 LaunchedEffect 被取消属正常流程，不能当成加载失败展示
            throw e
        } catch (e: Exception) {
            error = e.message ?: "直播源加载失败"
        } finally {
            loading = false
        }
    }

    if (currentSource == null) {
        EmptyState(
            icon = Icons.Outlined.LiveTv,
            title = "还没有直播源",
            description = "在设置里填写一个 m3u 或 txt 直播源地址，订阅自带的 lives 也会出现在这里",
        )
        return
    }

    when (formFactor) {
        FormFactor.Phone -> PhoneLayout(
            sources = sources,
            sourceIndex = sourceIndex,
            onSelectSource = { sourceIndex = it },
            groups = groups,
            groupIndex = groupIndex,
            onSelectGroup = { groupIndex = it },
            loading = loading,
            error = error,
            onRetry = { reloadKey++ },
            onPlayChannel = onPlayChannel,
            padding = padding,
        )

        else -> WideLayout(
            sources = sources,
            sourceIndex = sourceIndex,
            onSelectSource = { sourceIndex = it },
            groups = groups,
            groupIndex = groupIndex,
            onSelectGroup = { groupIndex = it },
            loading = loading,
            error = error,
            onRetry = { reloadKey++ },
            onPlayChannel = onPlayChannel,
            padding = padding,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneLayout(
    sources: List<LiveSourceEntry>,
    sourceIndex: Int,
    onSelectSource: (Int) -> Unit,
    groups: List<ChannelGroup>,
    groupIndex: Int,
    onSelectGroup: (Int) -> Unit,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPlayChannel: (Channel) -> Unit,
    padding: androidx.compose.ui.unit.Dp,
) {
    val formFactor = LocalFormFactor.current
    Column(Modifier.fillMaxSize()) {
        Text(
            "直播",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = padding, vertical = 12.dp),
        )
        if (sources.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = padding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(sources.size) { index ->
                    PillChip(
                        label = sources[index].name,
                        selected = index == sourceIndex,
                        onClick = { onSelectSource(index) },
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(groups.size) { index ->
                PillChip(
                    label = "${groups[index].name} ${groups[index].channels.size}",
                    selected = index == groupIndex,
                    onClick = { onSelectGroup(index) },
                )
            }
        }
        PullToRefreshBox(indicator = {}, isRefreshing = loading, onRefresh = onRetry, modifier = Modifier.weight(1f)) {
            when {
                loading && groups.isEmpty() -> LiveLoading()
                error != null -> EmptyState(
                    icon = Icons.Outlined.LiveTv,
                    title = "加载失败",
                    description = error,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
                groups.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LiveTv,
                    title = "没有可播放的频道",
                    description = "换一个直播源试试",
                    actionLabel = "重试",
                    onAction = onRetry,
                )
                else -> ChannelGrid(
                    channels = groups[groupIndex.coerceIn(0, groups.size - 1)].channels,
                    contentPadding = PaddingValues(horizontal = padding, vertical = 8.dp),
                    onPlayChannel = onPlayChannel,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WideLayout(
    sources: List<LiveSourceEntry>,
    sourceIndex: Int,
    onSelectSource: (Int) -> Unit,
    groups: List<ChannelGroup>,
    groupIndex: Int,
    onSelectGroup: (Int) -> Unit,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPlayChannel: (Channel) -> Unit,
    padding: androidx.compose.ui.unit.Dp,
) {
    val formFactor = LocalFormFactor.current
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(if (formFactor.isTv) 240.dp else 200.dp)
                .fillMaxHeight()
                .padding(start = padding, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text("直播", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            if (sources.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEachIndexed { index, entry ->
                        PillChip(
                            label = entry.name,
                            selected = index == sourceIndex,
                            onClick = { onSelectSource(index) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(groups.size) { index ->
                    PillChip(
                        label = "${groups[index].name} ${groups[index].channels.size}",
                        selected = index == groupIndex,
                        onClick = { onSelectGroup(index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        PullToRefreshBox(indicator = {}, isRefreshing = loading, onRefresh = onRetry, modifier = Modifier.weight(1f)) {
            when {
                loading && groups.isEmpty() -> LiveLoading()
                error != null -> EmptyState(
                    icon = Icons.Outlined.LiveTv,
                    title = "加载失败",
                    description = error,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
                groups.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LiveTv,
                    title = "没有可播放的频道",
                    description = "换一个直播源试试",
                    actionLabel = "重试",
                    onAction = onRetry,
                )
                else -> ChannelGrid(
                    channels = groups[groupIndex.coerceIn(0, groups.size - 1)].channels,
                    contentPadding = PaddingValues(end = padding, top = 12.dp, bottom = 12.dp),
                    onPlayChannel = onPlayChannel,
                )
            }
        }
    }
}

@Composable
private fun LiveLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = XColors.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                "正在加载直播源，首次可能需要几十秒",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChannelGrid(
    channels: List<Channel>,
    contentPadding: PaddingValues,
    onPlayChannel: (Channel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (LocalFormFactor.current.isTv) 180.dp else 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(channels, key = { it.name + it.url }) { channel ->
            ChannelCard(channel = channel, onClick = { onPlayChannel(channel) })
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(XRadii.md),
        focusScale = 1.06f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(XColors.Surface, RoundedCornerShape(XRadii.md))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(XColors.PosterPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(channel.logo)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        placeholder = ColorPainter(XColors.PosterPlaceholder),
                        error = ColorPainter(XColors.PosterPlaceholder),
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Icon(
                        Icons.Outlined.LiveTv,
                        contentDescription = null,
                        tint = XColors.TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(XSpacing.sm))
            Text(
                channel.name,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }
    }
}
