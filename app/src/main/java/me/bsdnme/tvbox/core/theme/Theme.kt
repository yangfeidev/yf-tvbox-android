package me.bsdnme.tvbox.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌 —— 圆角、间距与字体。
 *
 * 结构：
 * - [XRadii]：圆角梯度，卡片 10 / 浮层 16 / 胶囊 999
 * - [XSpacing]：8pt 栅格 + 电视安全边距
 * - [XTypography]：按是否电视整体放大 4sp，电视端远距离观看才看得清
 * - [LocalFormFactor]：把手机 / 平板 / 电视形态下发给整棵树，避免各页面自己探测
 */
object XRadii {
    val sm = 6.dp
    val md = 10.dp
    val lg = 16.dp
    val pill = 999.dp
}

object XSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 电视安全边距，1920x1080 下留 48dp，避免被电视过扫描裁掉 */
    val tvSafe = 48.dp
}

/** 设备形态。手机与平板共用触摸交互，电视走遥控器焦点。 */
enum class FormFactor {
    Phone,
    Tablet,
    Television;

    val isTv: Boolean get() = this == Television
    val isTablet: Boolean get() = this == Tablet
    val isPhone: Boolean get() = this == Phone

    /** 网格列数：手机 3 / 平板 5 / 电视 6 */
    val gridColumns: Int
        get() = when (this) {
            Phone -> 3
            Tablet -> 5
            Television -> 6
        }

    /** 页面左右安全边距 */
    val horizontalPadding: androidx.compose.ui.unit.Dp
        get() = when (this) {
            Phone -> 16.dp
            Tablet -> 24.dp
            Television -> 48.dp
        }
}

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }

/** 当前形态下的字号增量：电视 +4sp */
val LocalFontBoost = staticCompositionLocalOf { 0 }

private fun typography(boost: Int) = Typography(
    displaySmall = TextStyle(fontSize = (24 + boost).sp, fontWeight = FontWeight.SemiBold, color = XColors.TextPrimary),
    headlineMedium = TextStyle(fontSize = (20 + boost).sp, fontWeight = FontWeight.SemiBold, color = XColors.TextPrimary),
    headlineSmall = TextStyle(fontSize = (17 + boost).sp, fontWeight = FontWeight.SemiBold, color = XColors.TextPrimary),
    titleMedium = TextStyle(fontSize = (15 + boost).sp, fontWeight = FontWeight.Medium, color = XColors.TextPrimary),
    titleSmall = TextStyle(fontSize = (13 + boost).sp, fontWeight = FontWeight.Medium, color = XColors.TextPrimary),
    bodyLarge = TextStyle(fontSize = (14 + boost).sp, fontWeight = FontWeight.Normal, color = XColors.TextPrimary, lineHeight = (22 + boost).sp),
    bodyMedium = TextStyle(fontSize = (13 + boost).sp, fontWeight = FontWeight.Normal, color = XColors.TextSecondary, lineHeight = (21 + boost).sp),
    bodySmall = TextStyle(fontSize = (11 + boost).sp, fontWeight = FontWeight.Normal, color = XColors.TextSecondary, lineHeight = (17 + boost).sp),
    labelLarge = TextStyle(fontSize = (13 + boost).sp, fontWeight = FontWeight.Medium, color = XColors.TextPrimary),
)

private val xShapes = Shapes(
    extraSmall = RoundedCornerShape(XRadii.sm),
    small = RoundedCornerShape(XRadii.sm),
    medium = RoundedCornerShape(XRadii.md),
    large = RoundedCornerShape(XRadii.lg),
    extraLarge = RoundedCornerShape(XRadii.lg),
)

private val xColorScheme = darkColorScheme(
    primary = XColors.Accent,
    onPrimary = XColors.OnAccent,
    secondary = XColors.Info,
    onSecondary = XColors.TextPrimary,
    background = XColors.Background,
    onBackground = XColors.TextPrimary,
    surface = XColors.Background,
    onSurface = XColors.TextPrimary,
    surfaceVariant = XColors.Surface,
    onSurfaceVariant = XColors.TextSecondary,
    outline = XColors.Divider,
    error = XColors.Danger,
)

@Composable
fun FeigeMediaTheme(formFactor: FormFactor, content: @Composable () -> Unit) {
    val boost = if (formFactor.isTv) 4 else 0
    CompositionLocalProvider(
        LocalFormFactor provides formFactor,
        LocalFontBoost provides boost,
    ) {
        MaterialTheme(
            colorScheme = xColorScheme,
            typography = typography(boost),
            shapes = xShapes,
            content = content,
        )
    }
}
