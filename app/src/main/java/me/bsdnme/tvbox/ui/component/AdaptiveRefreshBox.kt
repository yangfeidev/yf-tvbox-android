package me.bsdnme.tvbox.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.bsdnme.tvbox.core.theme.LocalFormFactor

/** 遥控器焦点滚动不应触发触摸下拉手势；电视使用页面上的刷新按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalFormFactor.current.isTv) Box(modifier, content = content)
    else PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier, content = content)
}
