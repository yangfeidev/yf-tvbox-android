package me.bsdnme.tvbox.ui.component

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
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.core.theme.LocalFormFactor
import me.bsdnme.tvbox.data.model.MediaItem

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
    // 注意：不写死轨道高度。写死的话，海报(1.5x宽) + 标题 + 副标题的文本
    // 预算一旦不足，副标题下半就会被 LazyRow 视口裁掉；
    // 自适应高度时，上下 contentPadding(12dp) 仍足以容纳焦点放大 1.08 倍的溢出。

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
                modifier = Modifier.fillMaxWidth(),
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
