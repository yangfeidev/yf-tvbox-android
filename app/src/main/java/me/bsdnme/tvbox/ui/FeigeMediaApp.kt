package me.bsdnme.tvbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import me.bsdnme.tvbox.ui.component.TvTextButton as TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.ui.component.tvFocusBoundary
import me.bsdnme.tvbox.ui.component.TvFocusMemory
import me.bsdnme.tvbox.ui.component.LocalTvFocusMemory
import me.bsdnme.tvbox.ui.category.CategoryCache
import me.bsdnme.tvbox.ui.category.CategoryScreen
import me.bsdnme.tvbox.ui.detail.DetailScreen
import me.bsdnme.tvbox.ui.home.HomeScreen
import me.bsdnme.tvbox.ui.home.HomeSnapshot
import me.bsdnme.tvbox.ui.library.LibraryScreen
import me.bsdnme.tvbox.ui.live.LiveScreen
import me.bsdnme.tvbox.ui.player.PlaybackRequest
import me.bsdnme.tvbox.ui.search.SearchScreen
import me.bsdnme.tvbox.ui.settings.SettingsScreen
import me.bsdnme.tvbox.ui.shell.AppShell
import me.bsdnme.tvbox.ui.shell.Tab
import me.bsdnme.tvbox.vm.AppViewModel

/**
 * 应用根组合。
 *
 * 路由没有引入 navigation-compose：层级只有「Tab 页 → 详情 → 播放」三层，
 * 用一个可空状态变量表达反而更直观，也少一个版本依赖。
 *
 * 层叠顺序：Shell（含系统栏内边距）→ 详情（全屏）→ 播放（全屏，最上层）。
 * 系统栏内边距只加在 Shell 上，播放器要铺满整屏，不能被状态栏挤下去。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FeigeMediaApp(formFactor: FormFactor) {
    val vm: AppViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val modePrefs = remember { context.getSharedPreferences("content_mode", android.content.Context.MODE_PRIVATE) }
    var familyMode by remember { mutableStateOf(formFactor.isTv || modePrefs.getBoolean("family", false)) }
    LaunchedEffect(formFactor) { if (formFactor.isTv) familyMode = true }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val homeCache = remember(familyMode) { mutableMapOf<Site, HomeSnapshot>() }
    val categoryCache = remember(familyMode) { CategoryCache() }
    var focusHomeRequest by remember { mutableStateOf(0) }
    var detailTarget by remember { mutableStateOf<Pair<Site, MediaItem>?>(null) }
    var resumeFromHome by remember { mutableStateOf(false) }
    val playerPrefs = remember { context.getSharedPreferences("player_preferences", android.content.Context.MODE_PRIVATE) }
    var floatingPlayerEnabled by remember { mutableStateOf(playerPrefs.getBoolean("floating_player", true)) }
    fun startPlayback(request: PlaybackRequest) {
        me.bsdnme.tvbox.PlayerActivity.start(context, request, formFactor) { sourceName, episodeName, progress ->
            val site = request.site
            val detail = request.detail
            if (site != null && detail != null) vm.recordHistory(detail.item, site, sourceName, episodeName, progress)
        }
    }
    var searchVisible by remember { mutableStateOf(false) }
    var restoreNavigationFocus by remember { mutableStateOf(0) }
    val focusMemory = remember { TvFocusMemory() }
    var shellReturnTarget by remember { mutableStateOf<FocusRequester?>(null) }
    var searchReturnTarget by remember { mutableStateOf<FocusRequester?>(null) }
    val shellFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var restoreShellFocus by remember { mutableStateOf(0) }
    var restoreSearchFocus by remember { mutableStateOf(0) }
    fun saveFocus(requester: FocusRequester) {
        if (formFactor.isTv) {
            if (requester === shellFocus) shellReturnTarget = focusMemory.current else searchReturnTarget = focusMemory.current
            runCatching { requester.saveFocusedChild() }
        }
    }
    fun closeSearch() {
        searchVisible = false
        if (formFactor.isTv) restoreShellFocus++ else restoreNavigationFocus++
    }
    fun closeDetail() {
        detailTarget = null
        resumeFromHome = false
        if (formFactor.isTv) {
            if (searchVisible) restoreSearchFocus++ else restoreShellFocus++
        } else if (!searchVisible) restoreNavigationFocus++
    }
    LaunchedEffect(restoreShellFocus) {
        if (restoreShellFocus > 0 && formFactor.isTv) {
            androidx.compose.runtime.withFrameNanos { }
            val restored = shellReturnTarget?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } == true
            if (!restored && !runCatching { shellFocus.restoreFocusedChild() }.getOrDefault(false)) restoreNavigationFocus++
        }
    }
    LaunchedEffect(restoreSearchFocus) {
        if (restoreSearchFocus > 0 && formFactor.isTv) {
            androidx.compose.runtime.withFrameNanos { }
            val restored = searchReturnTarget?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } == true
            if (!restored) runCatching { searchFocus.restoreFocusedChild() }
        }
    }
    var contentHasFocus by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = detailTarget == null && !searchVisible && !confirmExit) {
        when {
            formFactor.isTv && contentHasFocus -> restoreNavigationFocus++
            tab != Tab.Home -> { tab = Tab.Home; if (formFactor.isTv) focusHomeRequest++ }
            else -> confirmExit = true
        }
    }
    if (confirmExit) {
        AlertDialog(
            modifier = Modifier.tvFocusBoundary(),
            onDismissRequest = { confirmExit = false },
            title = { Text("退出飞哥影视仓？") },
            text = { Text("确认要退出吗？") },
            confirmButton = { TextButton(onClick = { confirmExit = false; activity?.finish() }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("取消") } },
        )
    }

    CompositionLocalProvider(LocalTvFocusMemory provides focusMemory) {
    Box(Modifier.fillMaxSize().background(XColors.Background)) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .focusRequester(shellFocus)
                .focusGroup()
                .tvFocusBoundary(active = !searchVisible && detailTarget == null),
        ) {
            AppShell(
                formFactor = formFactor,
                selected = tab,
                onSelect = { tab = it },
                focusHomeRequest = focusHomeRequest,
                navigationActive = !searchVisible && detailTarget == null,
                restoreNavigationFocus = restoreNavigationFocus,
                onContentFocusChanged = { contentHasFocus = it },
            ) { current ->
                when (current) {
                    Tab.Home -> HomeScreen(
                        state = state,
                        familyMode = familyMode,
                        onOpenDetail = { site, item -> saveFocus(shellFocus); detailTarget = site to item },
                        onResume = { site, item -> saveFocus(shellFocus); resumeFromHome = true; detailTarget = site to item },
                        onOpenSearch = { saveFocus(shellFocus); searchVisible = true },
                        onOpenSettings = { tab = Tab.Settings },
                        cache = homeCache,
                    )

                    Tab.Category -> CategoryScreen(
                        state = state,
                        cache = categoryCache,
                        isActive = !searchVisible && detailTarget == null,
                        onReturnNavigation = { restoreNavigationFocus++ },
                        familyMode = familyMode,
                        onOpenDetail = { site, item -> saveFocus(shellFocus); detailTarget = site to item },
                        onOpenSearch = { saveFocus(shellFocus); searchVisible = true },
                    )

                    Tab.Live -> LiveScreen(
                        state = state,
                        vm = vm,
                        isActive = !searchVisible && detailTarget == null,
                        onReturnNavigation = { restoreNavigationFocus++ },
                        onPlayChannel = { channel ->
                            startPlayback(PlaybackRequest(channel = channel))
                        },
                    )

                    Tab.Library -> LibraryScreen(
                        state = state,
                        vm = vm,
                        familyMode = familyMode,
                        onOpenDetail = { site, item -> saveFocus(shellFocus); detailTarget = site to item },
                    )

                    Tab.Settings -> SettingsScreen(state = state, vm = vm, floatingPlayerEnabled = floatingPlayerEnabled, onFloatingPlayerChange = { floatingPlayerEnabled = it; playerPrefs.edit().putBoolean("floating_player", it).apply() }, familyMode = familyMode, onFamilyModeChange = { familyMode = it; modePrefs.edit().putBoolean("family", it).apply() }, onSiteChanged = {
                        tab = Tab.Home
                        focusHomeRequest++
                    })
                }
            }
        }

        if (searchVisible) {
            BackHandler(enabled = detailTarget == null) { closeSearch() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(XColors.Background)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .imePadding()
                    .focusRequester(searchFocus)
                    .focusGroup()
                    .tvFocusBoundary(active = detailTarget == null),
            ) {
                SearchScreen(
                    state = state,
                    familyMode = familyMode,
                    onOpenDetail = { site, item -> saveFocus(searchFocus); detailTarget = site to item },
                    onBack = { closeSearch() },
                    isActive = detailTarget == null,
                )
            }
        }

        detailTarget?.let { (site, item) ->
            BackHandler(enabled = true) { closeDetail() }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).tvFocusBoundary(active = true)) {
                DetailScreen(
                    vm = vm,
                    familyMode = familyMode,
                    site = site,
                    item = item,
                    onBack = { closeDetail() },
                    onPlay = { request -> startPlayback(request) },
                    isActive = true,
                    autoResume = resumeFromHome,
                    onAutoResumeHandled = { resumeFromHome = false },
                )
            }
        }

    }
    }
}
