package com.yangfei.xingmu.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.core.theme.XSpacing

/**
 * 通用小组件集合。
 *
 * - [EmptyState]：空态 / 错误态，统一「图标 + 标题 + 描述 + 可选操作」
 * - [PillChip]：胶囊标签，分类切换、线路切换共用；电视端自带焦点态
 * - [SectionTitle]：区块标题 + 右侧附加操作
 * - [LoadingBox]：居中加载
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(XSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(XColors.Surface, RoundedCornerShape(XRadii.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = XColors.TextSecondary)
        }
        Spacer(Modifier.height(XSpacing.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(XSpacing.sm))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(XSpacing.xl))
            FocusableSurface(
                onClick = onAction,
                shape = RoundedCornerShape(XRadii.pill),
                focusScale = 1.04f,
                showBorder = false,
            ) {
                Box(
                    modifier = Modifier
                        .background(XColors.Accent, RoundedCornerShape(XRadii.pill))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = XColors.OnAccent,
                    )
                }
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(XSpacing.sm))
            PillChip(secondaryActionLabel, selected = false, onClick = onSecondaryAction)
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = XColors.Accent, strokeWidth = 2.dp)
    }
}

@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val background by animateColorAsState(
        targetValue = if (selected) XColors.Accent else XColors.Surface,
        label = "chipBg",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) XColors.OnAccent else XColors.TextSecondary,
        label = "chipFg",
    )
    val shape = RoundedCornerShape(XRadii.pill)
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        focusScale = 1.04f,
        showBorder = true,
    ) {
        Row(
            modifier = Modifier
                .background(background, shape)
                .then(
                    if (selected) Modifier else Modifier.border(1.dp, XColors.Divider, shape),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = foreground)
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(18.dp).background(XColors.Accent, RoundedCornerShape(XRadii.pill)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing?.invoke()
    }
}

/** 小圆点状态灯，用于订阅卡片 */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .background(
                if (active) XColors.Success else XColors.TextTertiary,
                CircleShape,
            ),
    )
}

/** 统一的错误文案气泡 */
@Composable
fun InlineNotice(text: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(XColors.Surface, RoundedCornerShape(XRadii.md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) XColors.Danger else XColors.TextSecondary,
        )
    }
}
