package me.bsdnme.tvbox.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 设计令牌 —— 颜色。
 *
 * 与设计稿「深色影院风设计令牌」一一对应：
 * - 背景层：Background（页面底）/ Surface（卡片）/ SurfaceRaised（焦点、浮层）
 * - 分隔层：Divider（描边）/ Bar（占位条、进度槽）
 * - 语义层：Accent（主色，唯一强调色）/ Info / Success / Danger
 * - 文本层：TextPrimary / TextSecondary / TextTertiary
 *
 * 约束：全局只有一个强调色，不使用渐变与投影；
 * 电视端焦点态靠 2dp Accent 描边 + 1.08 缩放表达，不靠发光。
 */
object XColors {
    val Background = Color(0xFF0A0D14)
    val Surface = Color(0xFF171D29)
    val SurfaceRaised = Color(0xFF1E2637)
    val RailBackground = Color(0xFF0D121C)
    val PosterPlaceholder = Color(0xFF232C40)
    val PosterHighlight = Color(0xFF2C3752)

    val Divider = Color(0xFF2A3446)
    val Bar = Color(0xFF3A465C)

    val Accent = Color(0xFFFFB43C)
    val Info = Color(0xFF4C9AFF)
    val Success = Color(0xFF5AD18B)
    val Danger = Color(0xFFFF5C5C)

    val TextPrimary = Color(0xFFEDF1F7)
    val TextSecondary = Color(0xFF9AA8BC)
    val TextTertiary = Color(0xFF758299)

    val OnAccent = Color(0xFF0A0D14)
    val VideoBackground = Color(0xFF05070C)
    val Scrim = Color(0xE60B0E14)
}
