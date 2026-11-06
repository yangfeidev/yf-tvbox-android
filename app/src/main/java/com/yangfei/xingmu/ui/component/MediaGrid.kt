package com.yangfei.xingmu.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.data.model.MediaItem

/**
 * 响应式海报网格。
 *
 * 列数由设备形态决定：手机 3 / 平板 5 / 电视 6。
 * 宽高不写死比例，交给 [PosterCard] 内部的 2:3 约束自适应，
 * 否则不同列宽下标题会被裁掉。
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    progressProvider: ((MediaItem) -> Float?)? = null,
) {
    val formFactor = LocalFormFactor.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(formFactor.gridColumns),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.id + it.name }) { item ->
            PosterCard(
                item = item,
                onClick = { onItemClick(item) },
                progress = progressProvider?.invoke(item),
            )
        }
    }
}

/** 加载骨架：固定列数的占位块，避免内容到达时布局跳动 */
@Composable
fun GridSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val formFactor = LocalFormFactor.current
    val count = formFactor.gridColumns * rows
    LazyVerticalGrid(
        columns = GridCells.Fixed(formFactor.gridColumns),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(count) {
            PosterPlaceholder()
        }
    }
}
