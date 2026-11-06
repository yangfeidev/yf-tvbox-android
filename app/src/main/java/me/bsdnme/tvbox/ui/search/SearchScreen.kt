package me.bsdnme.tvbox.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import me.bsdnme.tvbox.data.LocalStore
import me.bsdnme.tvbox.core.theme.LocalFormFactor
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.core.theme.XRadii
import me.bsdnme.tvbox.data.model.MediaItem
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.net.VodApi
import me.bsdnme.tvbox.ui.component.EmptyState
import me.bsdnme.tvbox.ui.component.FocusableSurface
import me.bsdnme.tvbox.ui.component.GridSkeleton
import me.bsdnme.tvbox.ui.component.LoadingBox
import me.bsdnme.tvbox.ui.component.PillChip
import me.bsdnme.tvbox.ui.component.PosterCard
import me.bsdnme.tvbox.vm.AppUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页。
 *
 * 只搜当前源站：不同源的片库差异很大，跨源聚合属于 P1。
 * 电视端仍使用系统输入法，遥控器需通过机顶盒的「手机输入」录入中文。
 */
@Composable
fun SearchScreen(
    state: AppUiState,
    onOpenDetail: (Site, MediaItem) -> Unit,
    onBack: (() -> Unit)? = null,
    isActive: Boolean = true,
) {
    val formFactor = LocalFormFactor.current
    val padding = formFactor.horizontalPadding
    val site = state.currentSite
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember(context) { LocalStore(context) }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var hasAutoFocused by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(formFactor, isActive) {
        if (isActive && (!hasAutoFocused || formFactor == FormFactor.Television)) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { inputFocus.requestFocus() }
            if (formFactor != FormFactor.Television) keyboard?.show()
            hasAutoFocused = true
        }
    }

    var keyword by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(store.loadSearchHistory()) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var lastQuery by remember { mutableStateOf("") }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(1) }
    var moreLoading by remember { mutableStateOf(false) }
    var moreError by remember { mutableStateOf<String?>(null) }

    fun submit(text: String) {
        keyboard?.hide()
        if (formFactor != FormFactor.Television) focusManager.clearFocus()
        val current = site ?: return
        val query = text.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        lastQuery = query
        val generation = ++searchGeneration
        page = 1
        pageCount = 1
        moreLoading = false
        moreError = null
        searchJob = scope.launch {
            loading = true
            searched = true
            error = null
            results = emptyList()
            history = (listOf(query) + history.filter { it != query }).take(8)
            store.saveSearchHistory(history)
            try {
                val found = withContext(Dispatchers.IO) { VodApi.search(current, query) }
                results = found.items.distinctBy { it.id }
                page = found.page.coerceAtLeast(1)
                pageCount = found.pageCount
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
            } finally {
                if (generation == searchGeneration) loading = false
            }
        }
    }

    fun loadMore() {
        val current = site ?: return
        if (moreLoading || page >= pageCount) return
        val generation = searchGeneration
        val next = page + 1
        moreLoading = true
        moreError = null
        searchJob = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { VodApi.search(current, lastQuery, next) }
                if (generation == searchGeneration) {
                    results = (results + found.items).distinctBy { it.id }
                    if (found.items.isEmpty() || found.page < next) pageCount = page else {
                        page = found.page
                        pageCount = found.pageCount
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (generation == searchGeneration) moreError = e.message ?: "加载失败" }
            finally { if (generation == searchGeneration) moreLoading = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                FocusableSurface(
                    onClick = onBack,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    focusScale = 1.06f,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(XColors.Surface, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = XColors.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Text("搜索", style = MaterialTheme.typography.headlineSmall)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = keyword,
                onValueChange = { keyword = it; if (it.isBlank()) searched = false },
                modifier = Modifier.weight(1f)
                    .focusRequester(inputFocus)
                    .onFocusChanged { inputFocused = it.isFocused }
                    .border(if (inputFocused) 3.dp else 1.dp, if (inputFocused) Color.White else XColors.Divider, RoundedCornerShape(XRadii.pill)),
                singleLine = true,
                placeholder = {
                    Text("输入片名", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = XColors.TextSecondary)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(keyword) }),
                shape = RoundedCornerShape(XRadii.pill),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = XColors.Surface,
                    unfocusedContainerColor = XColors.Surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = XColors.TextPrimary,
                    unfocusedTextColor = XColors.TextPrimary,
                    cursorColor = XColors.Accent,
                ),
            )
            Spacer(Modifier.width(10.dp))
            FocusableSurface(
                onClick = { submit(keyword) },
                shape = RoundedCornerShape(XRadii.pill),
                focusScale = 1.05f,
                showBorder = false,
            ) {
                Box(
                    modifier = Modifier
                        .height(52.dp)
                        .background(XColors.Accent, RoundedCornerShape(XRadii.pill))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("搜索", style = MaterialTheme.typography.labelLarge, color = XColors.OnAccent)
                }
            }
        }

        if (history.isNotEmpty() && !searched) {
            Spacer(Modifier.height(16.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = padding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history.size) { index ->
                    PillChip(
                        label = history[index],
                        selected = false,
                        onClick = {
                            keyword = history[index]
                            submit(history[index])
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when {
                !searched -> EmptyState(
                    icon = Icons.Outlined.ManageSearch,
                    title = "搜索影视内容",
                    description = "搜索范围是当前源站，可在设置里切换源",
                )
                loading -> LoadingBox()
                error != null -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "搜索失败",
                    description = error,
                    actionLabel = "重试",
                    onAction = { submit(lastQuery) },
                )
                results.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "没有找到相关内容",
                    description = "换一个源站再试，不同源的片库差异很大",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(formFactor.gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = padding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(results, key = { it.id + it.name }) { item ->
                        PosterCard(item = item, onClick = {
                            keyboard?.hide()
                            if (formFactor != FormFactor.Television) focusManager.clearFocus()
                            site?.let { onOpenDetail(it, item) }
                        })
                    }
                    if (page < pageCount || moreLoading || moreError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (moreLoading) Text("正在加载更多…") else PillChip(
                                    label = if (moreError != null) "加载失败，点击重试" else "加载更多",
                                    selected = false, onClick = { loadMore() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
