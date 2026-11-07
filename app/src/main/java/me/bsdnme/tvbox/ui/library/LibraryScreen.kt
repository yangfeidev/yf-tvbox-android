package me.bsdnme.tvbox.ui.library

import me.bsdnme.tvbox.ui.component.tvFocusBoundary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.core.theme.LocalFormFactor
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.data.FamilyContent
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.model.toMediaItem
import me.bsdnme.tvbox.ui.component.EmptyState
import me.bsdnme.tvbox.ui.component.FocusableSurface
import me.bsdnme.tvbox.ui.component.MediaGrid
import me.bsdnme.tvbox.ui.component.PillChip
import me.bsdnme.tvbox.ui.component.PosterCard
import me.bsdnme.tvbox.vm.AppUiState
import me.bsdnme.tvbox.vm.AppViewModel

/**
 * 收藏与历史。
 *
 * 数据全部来自本地存储，不依赖源站可达性——
 * 源站挂了也能看到自己收藏过什么。
 * 历史项带观看进度条，点击回到详情继续播放。
 */
@Composable
fun LibraryScreen(
    state: AppUiState,
    vm: AppViewModel,
    familyMode: Boolean,
    onOpenDetail: (Site, MediaItem) -> Unit,
) {
    val formFactor = LocalFormFactor.current
    val padding = formFactor.horizontalPadding
    var showHistory by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }

    if (unavailable) AlertDialog(
            modifier = Modifier.tvFocusBoundary(),
        onDismissRequest = { unavailable = false },
        title = { Text("原片源不可用") },
        text = { Text("请切换回原来的订阅，或重新添加该片源后再打开。") },
        confirmButton = { TextButton(onClick = { unavailable = false }) { Text("知道了") } },
    )

    fun visible(entries: List<me.bsdnme.tvbox.data.model.LibraryEntry>) = if (!familyMode) entries else entries.filter { entry ->
        FamilyContent.libraryVisible(entry.toMediaItem(), state.usableSites.firstOrNull { it.key == entry.siteKey })
    }
    val favorites = visible(state.favorites)
    val history = visible(state.history)
    val entries = if (showHistory) history else favorites
    val site = state.currentSite

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (showHistory && entries.isNotEmpty() && !familyMode) {
                FocusableSurface(
                    onClick = { vm.clearHistory() },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    focusScale = 1.04f,
                    showBorder = false,
                ) {
                    Text(
                        "清空历史",
                        style = MaterialTheme.typography.bodySmall,
                        color = XColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(
                label = "收藏 ${favorites.size}",
                selected = !showHistory,
                onClick = { showHistory = false },
            )
            PillChip(
                label = "历史 ${history.size}",
                selected = showHistory,
                onClick = { showHistory = true },
            )
        }
        Box(Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                EmptyState(
                    icon = if (showHistory) Icons.Outlined.History else Icons.Outlined.BookmarkBorder,
                    title = if (showHistory) "还没有观看记录" else "还没有收藏",
                    description = if (showHistory) "播放过的内容会自动出现在这里" else "在详情页点收藏，方便下次找到",
                )
            } else if (showHistory) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(formFactor.gridColumns),
                    contentPadding = PaddingValues(horizontal = padding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(entries, key = { it.uniqueKey }) { entry ->
                        Column {
                            PosterCard(
                                item = entry.toMediaItem(),
                                progress = entry.progress.takeIf { it > 0f },
                                onClick = {
                                    val originalSite = state.usableSites.firstOrNull { it.key == entry.siteKey }
                                    if (originalSite != null) onOpenDetail(originalSite, entry.toMediaItem()) else unavailable = true
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                            PillChip(label = "删除记录", selected = false, onClick = { vm.removeHistory(entry) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(formFactor.gridColumns),
                    contentPadding = PaddingValues(horizontal = padding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(entries, key = { it.uniqueKey }) { entry ->
                        PosterCard(item = entry.toMediaItem(), onClick = {
                            val originalSite = state.usableSites.firstOrNull { it.key == entry.siteKey }
                            if (originalSite != null) onOpenDetail(originalSite, entry.toMediaItem()) else unavailable = true
                        })
                    }
                }
            }
        }
    }
}
