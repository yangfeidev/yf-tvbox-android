package me.bsdnme.tvbox.ui.settings

import me.bsdnme.tvbox.ui.component.tvFocusBoundary
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import me.bsdnme.tvbox.core.theme.FormFactor
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
import me.bsdnme.tvbox.ui.component.TvTextButton as TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.core.DefaultSources
import me.bsdnme.tvbox.core.theme.LocalFormFactor
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.core.theme.XRadii
import me.bsdnme.tvbox.data.model.LiveSourceEntry
import me.bsdnme.tvbox.data.model.Subscription
import me.bsdnme.tvbox.data.model.WarehouseEntry
import me.bsdnme.tvbox.ui.component.BrandMark
import me.bsdnme.tvbox.ui.component.CreatorSignature
import me.bsdnme.tvbox.ui.component.FocusableSurface
import me.bsdnme.tvbox.ui.component.InlineNotice
import me.bsdnme.tvbox.ui.component.PillChip
import me.bsdnme.tvbox.ui.component.SectionTitle
import me.bsdnme.tvbox.ui.component.StatusDot
import me.bsdnme.tvbox.ui.update.UpdateCenter
import me.bsdnme.tvbox.vm.AddResult
import me.bsdnme.tvbox.vm.AppUiState
import me.bsdnme.tvbox.vm.AppViewModel
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
fun SettingsScreen(state: AppUiState, vm: AppViewModel, floatingPlayerEnabled: Boolean, onFloatingPlayerChange: (Boolean) -> Unit, familyMode: Boolean, onFamilyModeChange: (Boolean) -> Unit, onSiteChanged: () -> Unit) {
    val padding = LocalFormFactor.current.horizontalPadding
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var pinDialog by remember { mutableStateOf<String?>(null) }
    pinDialog?.let { action ->
        PinDialog(changePin = action == "change", onDismiss = { pinDialog = null }, onUnlocked = { pinDialog = null; onFamilyModeChange(false) })
    }

    var url by remember { mutableStateOf(DefaultSources.BOX) }
    var name by remember { mutableStateOf("") }
    var liveUrl by remember(state.settings.liveUrl) {
        mutableStateOf(state.settings.liveUrl.ifBlank { DefaultSources.LIVE })
    }
    var submitting by remember { mutableStateOf(false) }
    var warehouse by remember { mutableStateOf<List<WarehouseEntry>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var siteQuery by remember { mutableStateOf("") }

    fun submit(targetUrl: String, targetName: String) {
        if (targetUrl.isBlank()) return
        scope.launch {
            submitting = true
            when (val result = vm.addSubscription(targetUrl, targetName)) {
                is AddResult.Success -> {
                    message = "订阅已添加"
                    name = ""
                    siteQuery = ""
                }
                is AddResult.NeedWarehouse -> warehouse = result.entries
                is AddResult.Failure -> message = result.message
            }
            submitting = false
        }
    }

    warehouse?.let { entries ->
        AlertDialog(
            modifier = Modifier.tvFocusBoundary(),
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
                SettingsTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入订阅地址") },
                    shape = RoundedCornerShape(XRadii.pill),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                CreatorSignature(
                    prefix = "",
                    suffix = " · 链接标识 bsdnme 取自名字的拼音首字母",
                    maxLines = 2,
                )
                Spacer(Modifier.height(10.dp))
                SettingsTextField(
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
                        if (submitting || state.addingSubscriptionUrl == url && url.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = XColors.OnAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在加载订阅，可能需要几十秒", style = MaterialTheme.typography.labelLarge, color = XColors.OnAccent)
                            }
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
            // 内置默认订阅被删除（或误删）后的一键恢复入口：
            // 平时默认订阅在列表里时隐藏，删了才出现，不占空间。
            // 复用添加逻辑（submit → addSubscription），成功后自动设为当前订阅。
            if (!state.subscriptions.any { it.url == DefaultSources.BOX }) {
                Spacer(Modifier.height(10.dp))
                FocusableSurface(
                    onClick = { submit(DefaultSources.BOX, "默认单仓") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(XRadii.md),
                    focusScale = 1.02f,
                    enabled = !submitting,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.md)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (submitting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = XColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在恢复默认订阅，可能需要几十秒", style = MaterialTheme.typography.labelLarge, color = XColors.TextSecondary)
                            }
                        } else {
                            Text("恢复默认订阅", style = MaterialTheme.typography.labelLarge, color = XColors.TextPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "内置单仓（tv.bsdnme.me/box）不在上面的列表里，点按钮一键恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = XColors.TextTertiary,
                )
            }
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
                    refreshing = state.refreshingSubscriptionId == state.subscriptions[index].id,
                    onActivate = { vm.activateSubscription(state.subscriptions[index].id) },
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
            SettingsTextField(
                value = siteQuery,
                onValueChange = { siteQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索源站名称") },
                shape = RoundedCornerShape(XRadii.pill),
                colors = fieldColors(),
            )
        }

        val prefix = "${state.settings.activeSubscriptionId}::"
        val visibleSites = state.usableSites.filter { it.name.contains(siteQuery.trim(), ignoreCase = true) }
            .sortedBy { if (prefix + it.key in state.pinnedSites) 0 else 1 }
        // 拆成多个懒加载项，订阅变化时更新列表，也避免大量源站一次性测量。
        val siteGroups = visibleSites.chunked(12)
        items(siteGroups.size) { index ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                siteGroups[index].forEach { site ->
                    val pinned = prefix + site.key in state.pinnedSites
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillChip(
                            label = site.name,
                            selected = state.currentSite?.key == site.key,
                            onClick = {
                                if (state.currentSite?.key != site.key) {
                                    vm.selectSite(site)
                                    onSiteChanged()
                                }
                            },
                        )
                        PillChip(label = if (pinned) "★" else "☆", selected = pinned,
                            modifier = Modifier.semantics { contentDescription = if (pinned) "取消置顶 ${site.name}" else "置顶 ${site.name}" },
                            onClick = { vm.togglePinnedSite(site) })
                    }
                }
            }
        }
        if (visibleSites.isEmpty() && siteQuery.isNotBlank()) {
            item { Text("没有找到匹配的源站", style = MaterialTheme.typography.bodySmall) }
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
                SettingsTextField(
                    value = liveUrl,
                    onValueChange = { liveUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入直播源地址") },
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
                label = "家庭模式",
                description = if (familyMode) "适合全家一起观看；切换个人模式需要 PIN" else "个人模式：显示全部内容；重新打开应用后电视恢复家庭模式",
                checked = familyMode,
                onCheckedChange = { if (it) onFamilyModeChange(true) else pinDialog = "unlock" },
            )
        }
        item {
            FocusableSurface(onClick = { pinDialog = "change" }, shape = RoundedCornerShape(XRadii.pill), focusScale = 1.04f) {
                Text("设置或修改 PIN", modifier = Modifier.background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.pill)).padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
        item {
            SwitchRow(
                label = "画中画播放",
                description = "离开播放器或应用时，以系统小窗口继续播放",
                checked = floatingPlayerEnabled,
                onCheckedChange = onFloatingPlayerChange,
            )
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
            // 关于卡片：品牌行 / 操作行 / 声明 三段式，间距统一用 12dp 分组
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(XColors.Surface, RoundedCornerShape(XRadii.lg))
                    .padding(16.dp),
            ) {
                // 品牌行：左 Logo + 名称，右版本号（从 PackageManager 读取，发版改 build.gradle.kts 后自动跟上）
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(modifier = Modifier.weight(1f))
                    Text(
                        "v${UpdateCenter.currentVersionName(context)} (${UpdateCenter.currentVersionCode(context)})",
                        style = MaterialTheme.typography.labelLarge,
                        color = XColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(XColors.Divider),
                )
                Spacer(Modifier.height(12.dp))
                // 操作行：两个紧凑小按钮并排（自适应内容宽度），主操作实底、次操作弱底色
                // 检查任务和弹窗独立于设置页，按钮保持焦点直到弹窗接管。
                val checkingUpdate = UpdateCenter.uiState == me.bsdnme.tvbox.ui.update.UpdateUiState.Checking
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusableSurface(
                        onClick = {
                            if (!checkingUpdate) UpdateCenter.manualCheck(context)
                        },
                        modifier = Modifier,
                        shape = RoundedCornerShape(XRadii.md),
                        focusScale = 1.02f,
                        showBorder = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .background(XColors.Accent, RoundedCornerShape(XRadii.md))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (checkingUpdate) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = XColors.OnAccent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    if (checkingUpdate) "检查中…" else "检查更新",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = XColors.OnAccent,
                                )
                            }
                        }
                    }
                    FocusableSurface(
                        onClick = { runCatching { uriHandler.openUri("https://bsdnme.me/") } },
                        modifier = Modifier,
                        shape = RoundedCornerShape(XRadii.md),
                        focusScale = 1.02f,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.md))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "个人网站 bsdnme.me",
                                style = MaterialTheme.typography.labelMedium,
                                color = XColors.TextPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "本应用不内置任何内容源，所有内容均来自你自行添加的订阅地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = XColors.TextTertiary,
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
    onActivate: () -> Unit,
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
        if (!active) {
            PillChip(label = "启用", selected = false, onClick = onActivate)
            Spacer(Modifier.width(6.dp))
        }
        PillChip(label = if (refreshing) "更新中…" else "更新", selected = false, onClick = onRefresh)
        Spacer(Modifier.width(6.dp))
        PillChip(label = "删除", selected = false, onClick = onRemove)
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val television = LocalFormFactor.current.isTv
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(XRadii.md)
    val focusStyle = if (television) Modifier
        .onFocusChanged { focused = it.hasFocus }
        .focusGroup()
        .background(if (focused) XColors.SurfaceRaised else Color.Transparent, shape)
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .padding(horizontal = 14.dp, vertical = 8.dp)
    else Modifier.padding(vertical = 8.dp)
    Row(
        modifier = Modifier.fillMaxWidth().then(focusStyle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
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

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    placeholder: @Composable (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape,
    colors: androidx.compose.material3.TextFieldColors,
) {
    val television = LocalFormFactor.current == FormFactor.Television
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            keyboard?.show()
        }
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused; if (!it.isFocused) editing = false }
            .then(if (television && focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .onPreviewKeyEvent { event ->
                if (!television || (editing && imeVisible) || event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        editing = true
                        if (editing) keyboard?.show()
                        true
                    }
                    Key.DirectionUp -> { editing = false; focusManager.moveFocus(FocusDirection.Up); true }
                    Key.DirectionDown -> { editing = false; focusManager.moveFocus(FocusDirection.Down); true }
                    Key.DirectionLeft -> { editing = false; focusManager.moveFocus(FocusDirection.Left); true }
                    Key.DirectionRight -> { editing = false; focusManager.moveFocus(FocusDirection.Right); true }
                    else -> false
                }
            },
        readOnly = television && !editing,
        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = !television),
        singleLine = singleLine,
        placeholder = placeholder,
        shape = shape,
        colors = colors,
    )
}
