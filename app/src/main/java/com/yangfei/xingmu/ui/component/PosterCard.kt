package com.yangfei.xingmu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.net.Http

/**
 * 海报卡。
 *
 * 结构（自上而下）：
 * 1. 海报区：2:3 比例、圆角 10、加载中与失败都回落成 [XColors.PosterPlaceholder]
 * 2. 观看进度条：仅历史场景传入 [progress] 时显示
 * 3. 标题：单行省略
 * 4. 副标题：年份 · 分类，单行省略
 *
 * 本组件只负责视觉，焦点描边与缩放由外层 [FocusableSurface] 负责，
 * 这样同一张卡在手机、平板、电视上表现一致，焦点逻辑不重复实现。
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    progress: Float? = null,
    showMeta: Boolean = true,
) {
    val cardModifier = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    FocusableSurface(
        onClick = onClick,
        modifier = cardModifier,
        shape = RoundedCornerShape(XRadii.md),
        focusScale = 1.08f,
    ) {
        Column(Modifier.padding(1.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(XRadii.md))
                    .background(XColors.PosterPlaceholder),
            ) {
                PosterImage(item.poster, item.name, Modifier.fillMaxSize())
                if (item.remarks.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(XColors.Scrim, RoundedCornerShape(XRadii.sm))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            item.remarks,
                            style = MaterialTheme.typography.bodySmall,
                            color = XColors.TextPrimary,
                            maxLines = 1,
                        )
                    }
                }
                if (progress != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(XColors.Divider),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(XColors.Accent),
                        )
                    }
                }
            }
            if (showMeta) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.year.takeIf { it.isNotBlank() },
                    item.categoryName.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun PosterImage(url: String, name: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(url.isBlank()) }
    Box(modifier.background(XColors.PosterPlaceholder), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url)
                    .setHeader("User-Agent", Http.DEFAULT_UA).crossfade(true).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(XColors.PosterPlaceholder),
                error = ColorPainter(XColors.PosterPlaceholder),
                onLoading = { failed = false },
                onSuccess = { failed = false },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (failed) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(XColors.PosterHighlight, XColors.Background))))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                BrandMark(size = 36.dp, showName = false)
                Spacer(Modifier.height(12.dp))
                Text(name, style = MaterialTheme.typography.titleSmall, color = XColors.TextPrimary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(if (url.isBlank()) "暂无海报" else "图片加载失败", style = MaterialTheme.typography.bodySmall, color = XColors.TextSecondary, maxLines = 1)
            }
        }
    }
}

/** 空白海报占位，用于骨架屏 */
@Composable
fun PosterPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(XRadii.md))
            .background(XColors.PosterPlaceholder),
    )
}
