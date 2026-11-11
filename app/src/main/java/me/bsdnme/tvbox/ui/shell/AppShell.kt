package me.bsdnme.tvbox.ui.shell

import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.R
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.core.theme.XRadii
import me.bsdnme.tvbox.ui.component.FocusableSurface
import me.bsdnme.tvbox.ui.component.BrandMark

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
    val icon: Int,
) {
    Home("首页", R.drawable.nav_home),
    Category("分类", R.drawable.nav_category),
    Live("直播", R.drawable.nav_live),
    Library("我的", R.drawable.nav_library),
    Settings("设置", R.drawable.nav_settings),
}

@Composable
fun AppShell(
    formFactor: FormFactor,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    focusHomeRequest: Int = 0,
    navigationActive: Boolean = true,
    restoreNavigationFocus: Int = 0,
    onContentFocusChanged: (Boolean) -> Unit = {},
    content: @Composable (Tab) -> Unit,
) {
    when (formFactor) {
        FormFactor.Phone -> PhoneShell(selected, onSelect, content)
        FormFactor.Tablet -> SidebarShell(selected, onSelect, content, wide = true, focusHomeRequest = focusHomeRequest, navigationActive = navigationActive, restoreNavigationFocus = restoreNavigationFocus, onContentFocusChanged = onContentFocusChanged)
        FormFactor.Television -> SidebarShell(selected, onSelect, content, wide = false, focusHomeRequest = focusHomeRequest, navigationActive = navigationActive, restoreNavigationFocus = restoreNavigationFocus, onContentFocusChanged = onContentFocusChanged)
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
    val color = if (selected) Color(0xFF2FE0D2) else XColors.TextSecondary
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
                    painterResource(tab.icon),
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
    focusHomeRequest: Int,
    navigationActive: Boolean,
    restoreNavigationFocus: Int,
    onContentFocusChanged: (Boolean) -> Unit,
) {
    val homeFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(restoreNavigationFocus) {
        if (!wide && restoreNavigationFocus > 0) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { selectedFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusHomeRequest, wide) {
        if (!wide && focusHomeRequest > 0) runCatching { homeFocus.requestFocus() }
    }
    val stateHolder = rememberSaveableStateHolder()
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
                    onFocus = { if (!wide && navigationActive && tab != selected) onSelect(tab) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                        .then(if (tab == Tab.Home) Modifier.focusRequester(homeFocus) else Modifier)
                        .then(if (tab == selected) Modifier.focusRequester(selectedFocus) else Modifier),
                )
            }
        }
        val contentModifier = if (!wide) Modifier.onFocusChanged { onContentFocusChanged(it.hasFocus) }.focusGroup() else Modifier
        Box(Modifier.weight(1f).then(contentModifier)) { stateHolder.SaveableStateProvider(selected) { content(selected) } }
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
    val color = if (selected) Color(0xFF2FE0D2) else XColors.TextSecondary
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(XRadii.md),
        focusScale = 1.04f,
        borderWidth = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    if (selected) Color(0xFF2FE0D2).copy(alpha = 0.14f) else XColors.RailBackground,
                    RoundedCornerShape(XRadii.md),
                )
                .padding(horizontal = if (wide) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(
                painterResource(tab.icon),
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
