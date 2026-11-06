package com.yangfei.xingmu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.net.Http

/**
 * 首页大图推荐位（B 区）。
 *
 * 遮罩用两层纯色而不是渐变：整体 0x59 压暗保证文字可读，底部 0x99 再压一次，
 * 与设计稿「无渐变」约束保持一致。
 */
@Composable
fun HeroBanner(
    item: MediaItem,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    tagline: String? = null,
    onDetail: (() -> Unit)? = null,
) {
    val formFactor = LocalFormFactor.current
    val height = when (formFactor) {
        FormFactor.Phone -> 220.dp
        FormFactor.Tablet -> 260.dp
        FormFactor.Television -> 320.dp
    }
    val horizontalPadding = formFactor.horizontalPadding
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .clip(RoundedCornerShape(XRadii.lg))
            .background(XColors.SurfaceRaised),
    ) {
        if (item.hasPoster) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.poster)
                    .setHeader("User-Agent", Http.DEFAULT_UA)
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(XColors.SurfaceRaised),
                error = ColorPainter(XColors.SurfaceRaised),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x330A0D14), Color(0x880A0D14), Color(0xF20A0D14)),
                ),
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            if (!tagline.isNullOrBlank()) {
                Text(
                    tagline,
                    style = MaterialTheme.typography.labelLarge,
                    color = XColors.Accent,
                    modifier = Modifier.background(XColors.Background.copy(alpha = 0.65f), RoundedCornerShape(XRadii.pill))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                item.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                item.year.takeIf { it.isNotBlank() },
                item.area.takeIf { it.isNotBlank() },
                item.categoryName.takeIf { it.isNotBlank() },
                item.remarks.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(label = "立即播放", onClick = onPlay)
                if (onDetail != null) {
                    SecondaryButton(label = "详情", onClick = onDetail)
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(XRadii.pill),
        focusScale = 1.05f,
        showBorder = false,
    ) {
        Box(
            modifier = Modifier
                .background(XColors.Accent, RoundedCornerShape(XRadii.pill))
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = XColors.OnAccent)
        }
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(XRadii.pill),
        focusScale = 1.05f,
        showBorder = false,
    ) {
        Box(
            modifier = Modifier
                .background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.pill))
                .padding(horizontal = 20.dp, vertical = 11.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = XColors.TextPrimary)
        }
    }
}

@Composable
fun SmallGhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(XRadii.sm),
        focusScale = 1.08f,
    ) {
        Box(
            modifier = Modifier
                .background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.sm))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = XColors.TextSecondary)
        }
    }
}
