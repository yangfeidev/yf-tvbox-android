package com.yangfei.xingmu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yangfei.xingmu.core.theme.FormFactor
import com.yangfei.xingmu.core.theme.XColors
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.model.Site
import com.yangfei.xingmu.ui.category.CategoryScreen
import com.yangfei.xingmu.ui.detail.DetailScreen
import com.yangfei.xingmu.ui.home.HomeScreen
import com.yangfei.xingmu.ui.library.LibraryScreen
import com.yangfei.xingmu.ui.live.LiveScreen
import com.yangfei.xingmu.ui.player.PlaybackRequest
import com.yangfei.xingmu.ui.player.PlayerScreen
import com.yangfei.xingmu.ui.search.SearchScreen
import com.yangfei.xingmu.ui.settings.SettingsScreen
import com.yangfei.xingmu.ui.shell.AppShell
import com.yangfei.xingmu.ui.shell.Tab
import com.yangfei.xingmu.vm.AppViewModel

/**
 * 应用根组合。
 *
 * 路由没有引入 navigation-compose：层级只有「Tab 页 → 详情 → 播放」三层，
 * 用一个可空状态变量表达反而更直观，也少一个版本依赖。
 *
 * 层叠顺序：Shell（含系统栏内边距）→ 详情（全屏）→ 播放（全屏，最上层）。
 * 系统栏内边距只加在 Shell 上，播放器要铺满整屏，不能被状态栏挤下去。
 */
@Composable
fun XingmuApp(formFactor: FormFactor) {
    val vm: AppViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var detailTarget by remember { mutableStateOf<Pair<Site, MediaItem>?>(null) }
    var playback by remember { mutableStateOf<PlaybackRequest?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = playback == null && detailTarget == null && !searchVisible && !confirmExit) {
        if (tab != Tab.Home) tab = Tab.Home else confirmExit = true
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出飞哥影视仓？") },
            text = { Text("确认要退出吗？") },
            confirmButton = { TextButton(onClick = { confirmExit = false; activity?.finish() }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } },
        )
    }

    Box(Modifier.fillMaxSize().background(XColors.Background)) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            AppShell(
                formFactor = formFactor,
                selected = tab,
                onSelect = { tab = it },
            ) { current ->
                when (current) {
                    Tab.Home -> HomeScreen(
                        state = state,
                        onOpenDetail = { site, item -> detailTarget = site to item },
                        onOpenSearch = { searchVisible = true },
                        onOpenSettings = { tab = Tab.Settings },
                    )

                    Tab.Category -> CategoryScreen(
                        state = state,
                        onOpenDetail = { site, item -> detailTarget = site to item },
                    )

                    Tab.Live -> LiveScreen(
                        state = state,
                        vm = vm,
                        onPlayChannel = { channel ->
                            playback = PlaybackRequest(channel = channel)
                        },
                    )

                    Tab.Library -> LibraryScreen(
                        state = state,
                        vm = vm,
                        onOpenDetail = { site, item -> detailTarget = site to item },
                    )

                    Tab.Settings -> SettingsScreen(state = state, vm = vm)
                }
            }
        }

        if (searchVisible) {
            BackHandler(enabled = detailTarget == null && playback == null) { searchVisible = false }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(XColors.Background)
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                SearchScreen(
                    state = state,
                    onOpenDetail = { site, item -> detailTarget = site to item },
                    onBack = { searchVisible = false },
                )
            }
        }

        detailTarget?.let { (site, item) ->
            BackHandler(enabled = playback == null) { detailTarget = null }
            DetailScreen(
                vm = vm,
                site = site,
                item = item,
                onBack = { detailTarget = null },
                onPlay = { request -> playback = request },
                isActive = playback == null,
            )
        }

        playback?.let { request ->
            PlayerScreen(
                request = request,
                onClose = { playback = null },
                onEpisodeChange = { },
                onProgress = { episodeName, progress ->
                    val site = request.site
                    val detail = request.detail
                    if (site != null && detail != null) {
                        vm.recordHistory(detail.item, site, episodeName, progress)
                    }
                },
            )
        }
    }
}
