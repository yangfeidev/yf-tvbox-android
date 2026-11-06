package com.yangfei.xingmu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.DefaultSources
import com.yangfei.xingmu.core.theme.LocalFormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.core.theme.XRadii
import com.yangfei.xingmu.data.model.LiveSourceEntry
import com.yangfei.xingmu.data.model.Subscription
import com.yangfei.xingmu.data.model.WarehouseEntry
import com.yangfei.xingmu.ui.component.BrandMark
import com.yangfei.xingmu.ui.component.FocusableSurface
import com.yangfei.xingmu.ui.component.InlineNotice
import com.yangfei.xingmu.ui.component.PillChip
import com.yangfei.xingmu.ui.component.SectionTitle
import com.yangfei.xingmu.ui.component.StatusDot
import com.yangfei.xingmu.vm.AddResult
import com.yangfei.xingmu.vm.AppUiState
import com.yangfei.xingmu.vm.AppViewModel
import kotlinx.coroutines.launch

/**
 * 设置页（订阅管理 + 偏好）。
 *
 * 结构：
 * - 添加订阅：地址 + 备注 + 按钮；多仓会弹窗选子仓库
 * - 已添加订阅：名称、地址、源数量、更新时间，可更新 / 删除
 * - 源站列表：可用的可点切换；spider 源标灰并注明原因
 * - 直播源：自定义 m3u / txt 地址；留空则用订阅自带的 lives
 * - 偏好：强制电视模式、启动自动更新
 *
 * 不做「导入文件 / 二维码」，P0 的输入路径只有粘贴地址一条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: AppUiState, vm: AppViewModel) {
    val padding = LocalFormFactor.current.horizontalPadding
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(DefaultSources.BOX) }
    var name by remember { mutableStateOf("") }
    var liveUrl by remember(state.settings.liveUrl) {
        mutableStateOf(state.settings.liveUrl.ifBlank { DefaultSources.LIVE })
    }
    var submitting by remember { mutableStateOf(false) }
    var warehouse by remember { mutableStateOf<List<WarehouseEntry>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun submit(targetUrl: String, targetName: String) {
        if (targetUrl.isBlank()) return
        scope.launch {
            submitting = true
            when (val result = vm.addSubscription(targetUrl, targetName)) {
                is AddResult.Success -> {
                    message = "订阅已添加"
                    url = ""
                    name = ""
                }
                is AddResult.NeedWarehouse -> warehouse = result.entries
                is AddResult.Failure -> message = result.message
            }
            submitting = false
        }
    }

    warehouse?.let { entries ->
        AlertDialog(
            onDismissRequest = { warehouse = null },
            title = { Text("这是多仓订阅") },
            text = {
                Column {
                    Text("请选择一个子仓库导入", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    entries.forEach { entry ->
                        FocusableSurface(
                            onClick = {
                                warehouse = null
                                submit(entry.url, entry.name)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = RoundedCornerShape(XRadii.md),
                            focusScale = 1.02f,
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(XColors.Surface, RoundedCornerShape(XRadii.md))
                                    .padding(12.dp),
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    entry.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = XColors.TextTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { warehouse = null }) { Text("取消") }
            },
            containerColor = XColors.SurfaceRaised,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = padding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("订阅与设置", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "订阅地址决定你能看到什么内容，源站不可用时先换源再排查",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(XColors.Surface, RoundedCornerShape(XRadii.lg))
                    .padding(16.dp),
            ) {
                Text("添加订阅", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://example.com/tvbox.json") },
                    shape = RoundedCornerShape(XRadii.pill),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("备注名称（可留空）") },
                    shape = RoundedCornerShape(XRadii.pill),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(10.dp))
                // 内置地址快捷填充，省掉手输（手机上中文输入法容易把 URL 转成候选词）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillChip(
                        label = "单仓 /box",
                        selected = url == DefaultSources.BOX,
                        onClick = { url = DefaultSources.BOX },
                    )
                    PillChip(
                        label = "多仓 /dc",
                        selected = url == DefaultSources.WAREHOUSE,
                        onClick = { url = DefaultSources.WAREHOUSE },
                    )
                    PillChip(
                        label = "直播 /live",
                        selected = liveUrl == DefaultSources.LIVE,
                        onClick = {
                            liveUrl = DefaultSources.LIVE
                            vm.setLiveUrl(DefaultSources.LIVE)
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                FocusableSurface(
                    onClick = { submit(url, name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(XRadii.md),
                    focusScale = 1.02f,
                    showBorder = false,
                    enabled = !submitting,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(XColors.Accent, RoundedCornerShape(XRadii.md)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = XColors.OnAccent,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(
                                "添加并加载",
                                style = MaterialTheme.typography.labelLarge,
                                color = XColors.OnAccent,
                            )
                        }
                    }
                }
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    InlineNotice(message!!, danger = state.error != null)
                }
            }
        }

        item {
            SectionTitle(
                title = "已添加的订阅",
                subtitle = "共 ${state.subscriptions.size} 个",
            )
        }

        if (state.error != null) {
            item { InlineNotice(state.error ?: "更新失败", danger = true) }
        }

        if (state.subscriptions.isEmpty()) {
            item {
                Text("还没有订阅，先在上面添加一个", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(state.subscriptions.size) { index ->
                SubscriptionTile(
                    subscription = state.subscriptions[index],
                    active = state.settings.activeSubscriptionId == state.subscriptions[index].id,
                    refreshing = state.loading,
                    onRefresh = { if (!state.loading) scope.launch { vm.refreshSubscription(state.subscriptions[index].id) } },
                    onRemove = { vm.removeSubscription(state.subscriptions[index].id) },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle(
                title = "源站",
                subtitle = if (state.skippedSiteCount > 0) {
                    "${state.skippedSiteCount} 个源暂不支持"
                } else {
                    "点击切换当前源"
                },
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.usableSites.forEach { site ->
                    PillChip(
                        label = site.name,
                        selected = state.currentSite?.key == site.key,
                        onClick = { vm.selectSite(site) },
                    )
                }
            }
        }

        if (state.usableSites.isEmpty()) {
            item {
                InlineNotice("当前订阅没有支持的源站（type 0 / 1 / 4）")
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle(
                title = "直播源",
                subtitle = "支持 m3u / txt；留空则使用订阅自带的 lives",
            )
        }

        item {
            Column {
                TextField(
                    value = liveUrl,
                    onValueChange = { liveUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://example.com/live.m3u") },
                    shape = RoundedCornerShape(XRadii.pill),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                FocusableSurface(
                    onClick = { vm.setLiveUrl(liveUrl.trim()) },
                    shape = RoundedCornerShape(XRadii.pill),
                    focusScale = 1.04f,
                ) {
                    Box(
                        modifier = Modifier
                            .background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.pill))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text("保存直播源", style = MaterialTheme.typography.labelLarge)
                    }
                }
                val sources: List<LiveSourceEntry> = state.document?.liveSources.orEmpty()
                if (sources.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "订阅内自带 ${sources.size} 个直播源",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle(title = "偏好设置")
        }

        item {
            SwitchRow(
                label = "强制电视模式",
                description = "在手机上预览遥控器布局，调试用",
                checked = state.settings.forceTelevision,
                onCheckedChange = { vm.setForceTelevision(it) },
            )
        }
        item {
            SwitchRow(
                label = "启动时自动更新订阅",
                description = "每次打开 App 拉取一次最新配置",
                checked = state.settings.autoUpdateOnLaunch,
                onCheckedChange = { vm.setAutoUpdate(it) },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle(title = "关于")
        }
        item {
            Column(Modifier.fillMaxWidth().background(XColors.Surface, RoundedCornerShape(XRadii.lg)).padding(16.dp)) {
                BrandMark()
                Spacer(Modifier.height(12.dp))
                Text("飞哥影视仓 v1.0.0 (1)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "本应用不内置任何内容源，所有内容均来自你自行添加的订阅地址。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = XColors.SurfaceRaised,
    unfocusedContainerColor = XColors.SurfaceRaised,
    focusedIndicatorColor = XColors.Accent,
    unfocusedIndicatorColor = Color.Transparent,
    focusedTextColor = XColors.TextPrimary,
    unfocusedTextColor = XColors.TextPrimary,
    cursorColor = XColors.Accent,
)

@Composable
private fun SubscriptionTile(
    subscription: Subscription,
    active: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(XColors.Surface, RoundedCornerShape(XRadii.md))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(subscription.name, style = MaterialTheme.typography.titleSmall)
            Text(
                subscription.url,
                style = MaterialTheme.typography.bodySmall,
                color = XColors.TextTertiary,
                maxLines = 1,
            )
            Text(
                "${subscription.siteCount} 个源 · ${relativeTime(subscription.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PillChip(label = if (refreshing) "更新中…" else "更新", selected = false, onClick = onRefresh)
        Spacer(Modifier.width(6.dp))
        PillChip(label = "删除", selected = false, onClick = onRemove)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = XColors.OnAccent,
                checkedTrackColor = XColors.Accent,
                uncheckedThumbColor = XColors.TextSecondary,
                uncheckedTrackColor = XColors.SurfaceRaised,
            ),
        )
    }
}

private fun relativeTime(time: Long?): String {
    if (time == null || time <= 0L) return "未更新"
    val diff = System.currentTimeMillis() - time
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "${minutes} 分钟前更新"
        minutes < 1440 -> "${minutes / 60} 小时前更新"
        else -> "${minutes / 1440} 天前更新"
    }
}
