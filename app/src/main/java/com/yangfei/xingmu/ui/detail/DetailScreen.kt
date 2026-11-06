package com.yangfei.xingmu.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yangfei.xingmu.core.theme.FormFactor
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.data.model.MediaDetail
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.model.PlaySource
import com.yangfei.xingmu.data.model.Site
import com.yangfei.xingmu.data.net.VodApi
import com.yangfei.xingmu.ui.component.EmptyState
import com.yangfei.xingmu.ui.component.FocusableSurface
import com.yangfei.xingmu.ui.component.LoadingBox
import com.yangfei.xingmu.ui.component.PillChip
import com.yangfei.xingmu.ui.component.PosterImage
import com.yangfei.xingmu.ui.player.PlaybackRequest
import com.yangfei.xingmu.vm.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 详情页。
 *
 * 结构：
 * - 顶部返回 + 海报
 * - 信息区：标题、元信息、简介、演员
 * - 线路切换 + 选集网格
 * - 底栏：收藏 / 立即播放
 *
 * 布局差异：手机上纵向单列；平板与电视上「左海报 + 右信息」两栏，
 * 因为横屏下纵向铺开会把选集推到屏幕之外。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vm: AppViewModel,
    site: Site,
    item: MediaItem,
    onBack: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    isActive: Boolean = true,
) {
    val formFactor = LocalFormFactor.current
    val padding = formFactor.horizontalPadding
    val wide = !formFactor.isPhone

    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(loading, detail, isActive) {
        if (formFactor.isTv && isActive && !loading && detail != null && error == null) {
            withFrameNanos { }
            runCatching { initialFocus.requestFocus() }
        }
    }

    LaunchedEffect(site, item.id, reloadKey) {
        loading = true
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) { VodApi.detail(site, item.id) }
            sourceIndex = loaded.usableSources.indexOfFirst { source -> source.episodes.any { it.isPlayable } }.coerceAtLeast(0)
            detail = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "详情加载失败"
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(XColors.Background)) {
        when {
            loading -> LoadingBox()
            error != null || detail == null -> {
                EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = "详情加载失败",
                    description = error,
                    actionLabel = "重试",
                    onAction = { reloadKey++ },
                    secondaryActionLabel = "返回",
                    onSecondaryAction = onBack,
                )
            }
            else -> {
                val data = detail!!
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = padding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackButton(onBack)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            data.item.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (wide) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = padding)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Poster(data, Modifier.width(220.dp))
                            Spacer(Modifier.width(24.dp))
                            InfoColumn(
                                data = data,
                                sourceIndex = sourceIndex,
                                expanded = expanded,
                                onToggleExpand = { expanded = !expanded },
                                onSelectSource = { sourceIndex = it },
                                onPlay = {
                                    onPlay(
                                        PlaybackRequest(
                                            site = site,
                                            detail = data,
                                            sourceIndex = sourceIndex,
                                            episodeIndex = it,
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                initialFocus = initialFocus,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = padding),
                        ) {
                            Row {
                                Poster(data, Modifier.width(120.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(data.item.name, style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(data.metaLine, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            InfoColumn(
                                data = data,
                                sourceIndex = sourceIndex,
                                expanded = expanded,
                                onToggleExpand = { expanded = !expanded },
                                onSelectSource = { sourceIndex = it },
                                onPlay = {
                                    onPlay(
                                        PlaybackRequest(
                                            site = site,
                                            detail = data,
                                            sourceIndex = sourceIndex,
                                            episodeIndex = it,
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                showTitle = false,
                                initialFocus = initialFocus,
                            )
                        }
                    }
                    BottomBar(
                        favorite = vm.isFavorite(site.key, data.item.id),
                        onToggleFavorite = { vm.toggleFavorite(data.item, site) },
                        onPlay = {
                            onPlay(
                                PlaybackRequest(
                                    site = site,
                                    detail = data,
                                    sourceIndex = sourceIndex,
                                    episodeIndex = 0,
                                ),
                            )
                        },
                        padding = padding,
                        focusRequester = if (data.usableSources.getOrNull(sourceIndex)?.episodes.isNullOrEmpty()) initialFocus else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Poster(data: MediaDetail, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(XRadii.lg))
            .background(XColors.PosterPlaceholder),
    ) {
        PosterImage(data.item.poster, data.item.name, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoColumn(
    data: MediaDetail,
    sourceIndex: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectSource: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    initialFocus: FocusRequester,
) {
    val sources = data.usableSources
    val current: PlaySource? = sources.getOrNull(sourceIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)))

    Column(modifier = modifier) {
        if (showTitle) {
            Text(data.item.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(data.metaLine, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        if (data.director.isNotBlank() || data.actor.isNotBlank()) {
            Text(
                listOfNotNull(
                    data.director.takeIf { it.isNotBlank() }?.let { "导演 $it" },
                    data.actor.takeIf { it.isNotBlank() }?.let { "主演 $it" },
                ).joinToString("   "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (data.content.isNotBlank()) {
            FocusableSurface(
                onClick = onToggleExpand,
                shape = RoundedCornerShape(XRadii.sm),
                focusScale = 1.01f,
                showBorder = false,
            ) {
                Text(
                    data.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                    modifier = Modifier.padding(2.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (sources.isEmpty()) {
            Text(
                "这个源没有返回可播放的线路，换一个源试试",
                style = MaterialTheme.typography.bodySmall,
                color = XColors.Info,
            )
        } else {
            Text("播放线路", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEachIndexed { index, source ->
                    PillChip(
                        label = source.name,
                        selected = index == sourceIndex,
                        onClick = { onSelectSource(index) },
                    )
                }
            }
            if (current != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "选集 · 共 ${current.episodes.size} 集",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    current.episodes.forEachIndexed { index, episode ->
                        FocusableSurface(
                            onClick = { onPlay(index) },
                            modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                            shape = RoundedCornerShape(XRadii.sm),
                            focusScale = 1.08f,
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(XColors.Surface, RoundedCornerShape(XRadii.sm))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    episode.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = XColors.TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BottomBar(
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    padding: androidx.compose.ui.unit.Dp,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(XColors.Background)
            .padding(horizontal = padding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusableSurface(
            onClick = onToggleFavorite,
            shape = RoundedCornerShape(XRadii.md),
            focusScale = 1.04f,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(XColors.Surface, RoundedCornerShape(XRadii.md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = "收藏",
                    tint = if (favorite) XColors.Accent else XColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        FocusableSurface(
            onClick = onPlay,
            modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = RoundedCornerShape(XRadii.md),
            focusScale = 1.02f,
            showBorder = false,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(XColors.Accent, RoundedCornerShape(XRadii.md)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "立即播放",
                    style = MaterialTheme.typography.labelLarge,
                    color = XColors.OnAccent,
                )
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    FocusableSurface(
        onClick = onBack,
        shape = CircleShape,
        focusScale = 1.06f,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(XColors.Surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = XColors.TextPrimary, modifier = Modifier.size(18.dp))
        }
    }
}
