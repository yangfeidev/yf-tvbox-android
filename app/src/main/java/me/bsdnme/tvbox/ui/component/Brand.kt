package me.bsdnme.tvbox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.R
import me.bsdnme.tvbox.core.theme.XColors

/** 流光只作用于名字，其他说明文字使用普通颜色。 */
@Composable
fun CreatorSignature(
    modifier: Modifier = Modifier,
    prefix: String = "由 ",
    suffix: String = " 打造",
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 1,
) {
    var textWidth by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "creator shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "creator shimmer position",
    )
    val width = textWidth.coerceAtLeast(1).toFloat()
    val highlight = progress * width
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFFFFB43C), Color(0xFFFFE7A6), Color.White, Color(0xFFFFD17B), Color(0xFFFFB43C)),
        start = Offset(highlight - width * 0.5f, 0f),
        end = Offset(highlight + width * 0.5f, 0f),
    )
    Text(
        text = buildAnnotatedString {
            append(prefix)
            withStyle(SpanStyle(brush = brush, fontWeight = FontWeight.SemiBold)) { append("巴萨的内马尔") }
            append(suffix)
        },
        modifier = modifier.onSizeChanged { textWidth = it.width },
        style = style,
        color = XColors.TextSecondary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 应用内统一的品牌标识，适配手机标题和电视导航。 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 36.dp, showName: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.mipmap.ic_launcher), contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)))
        if (showName) {
            Spacer(Modifier.width(10.dp))
            Column {
                Text("飞哥影视仓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = XColors.TextPrimary, maxLines = 1)
                CreatorSignature()
            }
        }
    }
}
