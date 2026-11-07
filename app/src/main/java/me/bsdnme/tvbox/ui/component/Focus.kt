package me.bsdnme.tvbox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import me.bsdnme.tvbox.core.theme.LocalFormFactor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.core.theme.XRadii

/**
 * 焦点容器 —— 电视端一切可点元素的基座。
 *
 * 行为：
 * - 获得焦点时按 [focusScale] 放大，并画 2dp 主色描边（描边画在 clip 之前，不会被裁掉半条）
 * - OK 键 / Enter / 空格触发点击；D-pad 方向键交给 Compose 默认的几何焦点搜索
 * - 触摸端同样是 clickable，所以手机、平板复用同一个组件
 *
 * 为什么不用 androidx.tv:tv-material：
 * 它的焦点样式封装较死，且 beta 版本与 Compose 1.7 的兼容性不稳；
 * 自研这 40 行反而更好控制，也少一个大依赖。
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(XRadii.md),
    focusScale: Float = 1.06f,
    borderWidth: Dp = 2.dp,
    enabled: Boolean = true,
    showBorder: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        label = "focusScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused && borderWidth > 0.dp) {
                    Modifier.border(if (showBorder) borderWidth + 1.dp else borderWidth, Color.White, shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .clickable(enabled = enabled) { onClick() },
    ) {
        content(focused)
    }
}

/** 无焦点态的普通点击容器，用于手机/平板上的轻量元素 */
@Composable
fun TapSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(XRadii.md),
    color: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
            .clickable { onClick() },
    ) {
        content()
    }
}

/** 限制电视方向键在当前页面或弹窗内；边界没有目标时保留原焦点。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.tvFocusBoundary(active: Boolean = true): Modifier = composed {
    if (!active || !LocalFormFactor.current.isTv) return@composed this
    val manager = LocalFocusManager.current
    this
        .onKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> null
            }
            if (direction == null) false else {
                if (event.type == KeyEventType.KeyDown) manager.moveFocus(direction)
                true
            }
        }
        .focusProperties { exit = { FocusRequester.Cancel } }
        .focusGroup()
}
