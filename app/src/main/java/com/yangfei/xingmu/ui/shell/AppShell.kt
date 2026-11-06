package com.yangfei.xingmu.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.FormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.ui.component.FocusableSurface
import com.yangfei.xingmu.ui.component.BrandMark

/**
 * 导航骨架 —— 三种形态各一套，页面内容复用。
 *
 * | 形态 | 导航 | 位置 |
 * |---|---|---|
 * | 手机 | 底部 5 栏 | 拇指可达区 |
 * | 平板 | 左侧 200dp 侧边栏 | 利用横屏多出来的宽度 |
 * | 电视 | 左侧 96dp 图标轨道 | 遥控器方向键上下切换 |
 */
enum class Tab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("首页", Icons.Outlined.Home, Icons.Filled.Home),
    Category("分类", Icons.Outlined.Category, Icons.Filled.Category),
    Live("直播", Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    Library("我的", Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark),
    Settings("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
fun AppShell(
    formFactor: FormFactor,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    content: @Composable (Tab) -> Unit,
) {
    when (formFactor) {
        FormFactor.Phone -> PhoneShell(selected, onSelect, content)
        FormFactor.Tablet -> SidebarShell(selected, onSelect, content, wide = true)
        FormFactor.Television -> SidebarShell(selected, onSelect, content, wide = false)
    }
}

@Composable
private fun PhoneShell(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    content: @Composable (Tab) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(XColors.Background)) {
        Box(Modifier.weight(1f)) { content(selected) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(XColors.RailBackground),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                BottomItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) XColors.Accent else XColors.TextSecondary
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        FocusableSurface(
            onClick = onClick,
            shape = RoundedCornerShape(XRadii.md),
            focusScale = 1.04f,
            showBorder = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (selected) tab.selectedIcon else tab.icon,
                    contentDescription = tab.label,
                    tint = color,
                    modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(tab.label, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

@Composable
private fun SidebarShell(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    content: @Composable (Tab) -> Unit,
    wide: Boolean,
) {
    val railWidth = if (wide) 200.dp else 96.dp
    Row(Modifier.fillMaxSize().background(XColors.Background)) {
        Column(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(XColors.RailBackground)
                .padding(vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (wide) 20.dp else 0.dp, vertical = 6.dp),
                contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center,
            ) {
                BrandMark(size = if (wide) 34.dp else 38.dp, showName = wide)
            }
            Spacer(Modifier.height(12.dp))
            Tab.entries.forEach { tab ->
                RailItem(
                    tab = tab,
                    selected = tab == selected,
                    wide = wide,
                    onClick = { onSelect(tab) },
                    onFocus = { if (!wide && tab != selected) onSelect(tab) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) { content(selected) }
    }
}

@Composable
private fun RailItem(
    tab: Tab,
    selected: Boolean,
    wide: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) XColors.Accent else XColors.TextSecondary
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(XRadii.md),
        focusScale = 1.04f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    if (selected) XColors.Accent.copy(alpha = 0.12f) else XColors.RailBackground,
                    RoundedCornerShape(XRadii.md),
                )
                .padding(horizontal = if (wide) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(
                if (selected) tab.selectedIcon else tab.icon,
                contentDescription = tab.label,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            if (wide) {
                Spacer(Modifier.width(12.dp))
                Text(tab.label, style = MaterialTheme.typography.titleSmall, color = color)
            }
        }
    }
}
