package com.yangfei.xingmu.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.FormFactor
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.data.model.MediaItem

/**
 * 横向海报轨道 —— 电视首页的核心元素。
 *
 * 关键细节：轨道上下各留 12dp 内边距。
 * 焦点卡会放大到 1.08 倍并向四周溢出约 4dp，
 * 不留这个边距的话，LazyRow 的视口会把放大后的卡片裁掉一圈。
 */
@Composable
fun MediaRail(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    emptyHint: String = "暂无内容",
) {
    val formFactor = LocalFormFactor.current
    val horizontalPadding = formFactor.horizontalPadding
    val cardWidth = railCardWidth(formFactor)
    // 海报 2:3 + 标题两行 + 焦点溢出
    val railHeight = cardWidth * 1.5f + 56.dp

    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(
            title = title,
            modifier = Modifier.padding(horizontal = horizontalPadding),
            trailing = trailing,
        )
        Spacer(Modifier.height(4.dp))
        if (items.isEmpty()) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 12.dp),
            )
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(railHeight),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id + it.name }) { item ->
                    PosterCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        width = cardWidth,
                    )
                }
            }
        }
    }
}

/** 轨道卡片宽度，按形态分别给值：电视端要远距离可读，卡片更大 */
fun railCardWidth(formFactor: FormFactor): Dp = when (formFactor) {
    FormFactor.Phone -> 108.dp
    FormFactor.Tablet -> 122.dp
    FormFactor.Television -> 152.dp
}
