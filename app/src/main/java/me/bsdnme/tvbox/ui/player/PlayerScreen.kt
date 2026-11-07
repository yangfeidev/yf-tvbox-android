package me.bsdnme.tvbox.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import me.bsdnme.tvbox.ui.component.tvFocusBoundary
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.core.theme.XRadii
import me.bsdnme.tvbox.data.model.Channel
import me.bsdnme.tvbox.data.model.MediaDetail
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.net.Http
import me.bsdnme.tvbox.data.net.VodApi
import me.bsdnme.tvbox.ui.component.FocusableSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放请求。点播与直播共用同一套播放器：
 * - 点播：[detail] 非空，按 [sourceIndex] / [episodeIndex] 取直链
 * - 直播：[channel] 非空，单流播放
 */
data class PlaybackRequest(
    val site: Site? = null,
    val detail: MediaDetail? = null,
    val sourceIndex: Int = 0,
    val episodeIndex: Int = 0,
    val resumeProgress: Float = 0f,
    val channel: Channel? = null,
) {
    val isLive: Boolean get() = channel != null

    val title: String
        get() = channel?.name ?: detail?.item?.name.orEmpty()

    val episodeName: String
        get() = channel?.group
            ?: detail?.usableSources?.getOrNull(sourceIndex)?.episodes?.getOrNull(episodeIndex)?.name
            ?: ""
}

/**
 * 播放页。
 *
 * 结构：
 * - 视频层：ExoPlayer + PlayerView（关掉自带控制条，用自绘的）
 * - 顶部：返回 + 标题
 * - 底部：进度条 + 上一集 / 快退 10s / 播放暂停 / 快进 10s / 下一集 + 选集入口
 *
 * 遥控器逻辑：
 * - 画面焦点下左右键快进快退 10 秒，长按连续调整；控制栏焦点下保留方向导航，长按可调整进度
 * - 上下键呼出控制层；OK 键在控制层隐藏时呼出，显示时暂停/播放
 * - 控制层出现时焦点自动落到播放/暂停主按钮，方向键随即在控制层内做焦点导航，
 *   可以上移到左上角返回按钮后按 OK 退出
 * - 返回键（Back）直接退出播放器
 * - 触摸端点一下视频区域切换控制层显隐，左右滑动预览并调整进度
 *
 * 控制层 4 秒无操作自动收起，但暂停时不收（否则用户看不到播放按钮）；
 * 显示期间的任何按键 / 点击 / 焦点移动都会重置倒计时。
 */
@Composable
fun PlayerScreen(
    request: PlaybackRequest,
    onClose: () -> Unit,
    onEpisodeChange: ((Int) -> Unit)? = null,
    onProgress: ((sourceName: String, episodeName: String, progress: Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    var episodeIndex by remember { mutableIntStateOf(request.episodeIndex) }
    var sourceIndex by remember { mutableIntStateOf(request.sourceIndex) }
    var retryKey by remember { mutableIntStateOf(0) }
    var showCast by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<SeekPreview?>(null) }
    var seeking by remember { mutableStateOf(false) }
    var seekingKey by remember { mutableStateOf<Key?>(null) }
    var lastSeekKeyTime by remember { mutableLongStateOf(0L) }
    var previewTick by remember { mutableIntStateOf(0) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showEpisodePicker by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var focusControls by remember { mutableStateOf(true) }
    // 用户操作计数：任何按键 / 点击 / 焦点移动都 +1，用来重置「4 秒自动收起」计时
    var interactionTick by remember { mutableIntStateOf(0) }
    // 控制条出现时焦点落到播放/暂停主按钮上，遥控器方向键才能在控制条内导航到返回按钮
    val playFocusRequester = remember { FocusRequester() }
    val playerFocusRequester = remember { FocusRequester() }
    val errorFocusRequester = remember { FocusRequester() }
    val pickerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showSourcePicker, showEpisodePicker) {
        if (showSourcePicker || showEpisodePicker) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { pickerFocusRequester.requestFocus() }
        }
    }
    var playerHasFocus by remember { mutableStateOf(false) }
    var played by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(true) }
    var pendingResume by remember { mutableStateOf(request.resumeProgress.coerceIn(0f, 0.98f)) }
    val currentEpisode by rememberUpdatedState(episodeIndex)
    val currentSource by rememberUpdatedState(sourceIndex)
    val progressCallback by rememberUpdatedState(onProgress)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var playError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playError) {
        if (playError != null) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { errorFocusRequester.requestFocus() }
        }
    }

    val sources = request.detail?.usableSources.orEmpty()
    val episodes = sources.getOrNull(sourceIndex)?.episodes.orEmpty()
    val latestEpisodes by rememberUpdatedState(episodes)
    val rawUrl: String? = request.channel?.url ?: episodes.getOrNull(episodeIndex)?.url
    var resolved by remember { mutableStateOf<VodApi.Playback?>(null) }
    val url = resolved?.url

    val httpFactory = remember { DefaultHttpDataSource.Factory().setUserAgent(Http.DEFAULT_UA).setAllowCrossProtocolRedirects(true) }
    val player = remember {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)).build().apply {
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            if (!request.isLive && played && pendingResume == 0f) {
                val length = player.duration
                val progress = if (length > 0) (player.currentPosition.toFloat() / length).coerceIn(0f, 1f) else 0f
                val source = sources.getOrNull(currentSource)
                progressCallback?.invoke(source?.name.orEmpty(), source?.episodes?.getOrNull(currentEpisode)?.name.orEmpty(), progress)
            }
            player.release()
        }
    }

    // 播放或缓冲时保持屏幕唤醒；暂停、结束和离开播放器后恢复系统行为。
    val hostView = LocalView.current
    DisposableEffect(player, hostView) {
        val previousKeepScreenOn = hostView.keepScreenOn
        fun updateScreenAwake() {
            hostView.keepScreenOn = previousKeepScreenOn || (player.playWhenReady &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING))
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = updateScreenAwake()
        }
        player.addListener(listener)
        updateScreenAwake()
        onDispose {
            player.removeListener(listener)
            hostView.keepScreenOn = previousKeepScreenOn
        }
    }

    // 播放器全屏横屏；退出时恢复原方向
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                original ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // 全屏播放隐藏系统栏（状态栏 + 导航栏），避免系统状态栏压在左上角返回按钮上；
    // sticky 模式下从边缘滑入系统栏会短暂浮现又自动消失，退出播放器后恢复显示。
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, hostView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 播放错误要显式抛给 UI，否则只能看到一个永远转不出来的黑屏
    DisposableEffect(player, url, request.channel) {
        var retried = false
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!retried && !url.isNullOrBlank() &&
                    (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                     error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)) {
                    retried = true
                    player.setMediaItem(buildMediaItem(url, !initialHls(url, request.isLive)))
                    player.prepare()
                    player.play()
                    return
                }
                playError = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "播放失败：源返回的内容不是支持的视频流，可能需要解析或源已失效"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "播放失败：无法连接播放源，请检查网络或更换线路"
                    else -> "播放失败：${error.errorCodeName}"
                }
                controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(rawUrl, request.site, sourceIndex, retryKey) {
        seekPreview = null
        seeking = false
        seekingKey = null
        player.stop()
        resolved = null
        resolving = true
        if (rawUrl.isNullOrBlank()) {
            playError = "没有可播放的地址"
            resolving = false
            return@LaunchedEffect
        }
        playError = null
        try {
            val playback = if (request.site?.type == 4 && !request.isLive) {
                withContext(Dispatchers.IO) { VodApi.resolvePlayback(request.site, sources.getOrNull(sourceIndex)?.name.orEmpty(), rawUrl) }
            } else VodApi.Playback(rawUrl, request.channel?.headers.orEmpty())
            resolved = playback
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            playError = "播放失败：${e.message ?: "解析失败"}"
        } finally {
            resolving = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && pendingResume > 0f && player.duration > 0) {
                    player.seekTo((player.duration * pendingResume).toLong())
                    pendingResume = 0f
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(resolved) {
        val playback = resolved ?: return@LaunchedEffect
        runCatching {
            httpFactory.setDefaultRequestProperties(playback.headers)
            player.setMediaItem(buildMediaItem(playback.url, initialHls(playback.url, request.isLive)))
            player.prepare()
            player.play()
        }.onFailure { playError = "播放失败：${it.message}" }
    }

    // 进度轮询
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            if (player.isPlaying && !played && !request.isLive) {
                played = true
                if (pendingResume == 0f) progressCallback?.invoke(sources.getOrNull(sourceIndex)?.name.orEmpty(), latestEpisodes.getOrNull(episodeIndex)?.name.orEmpty(), if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f)
            }
            delay(500)
        }
    }

    LaunchedEffect(previewTick, seeking) {
        if (!seeking && seekPreview != null) {
            delay(1200)
            seekPreview = null
        }
    }

    // 无操作自动收起：interactionTick 变化（任何用户操作）都会重置 4 秒倒计时
    LaunchedEffect(controlsVisible, isPlaying, interactionTick, showSourcePicker, showEpisodePicker, seeking, showCast) {
        if (controlsVisible && isPlaying && !showSourcePicker && !showEpisodePicker && !seeking && !showCast) {
            delay(4000)
            controlsVisible = false
            focusControls = false
            runCatching { playerFocusRequester.requestFocus() }
        }
    }

    // 控制条出现时把焦点交给播放/暂停主按钮；
    // 否则没有任何元素持有焦点，遥控器方向键会被外层按键拦截吃掉，
    // 焦点永远到不了返回按钮（「看得见点不着」的根因）。
    // 控制条收起时按钮移出组合树，焦点自动释放，按键重新回到外层 Box。
    LaunchedEffect(controlsVisible, focusControls) {
        if (controlsVisible && focusControls) {
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    // 每 10 秒回写一次观看进度
    LaunchedEffect(Unit) {
        if (request.isLive) return@LaunchedEffect
        while (true) {
            delay(10_000)
            if (duration > 0 && played && pendingResume == 0f) {
                progressCallback?.invoke(
                    sources.getOrNull(sourceIndex)?.name.orEmpty(),
                    latestEpisodes.getOrNull(episodeIndex)?.name.orEmpty(),
                    (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
                )
            }
        }
    }

    BackHandler {
        when {
            showSourcePicker -> showSourcePicker = false
            showEpisodePicker -> showEpisodePicker = false
            else -> onClose()
        }
    }

    fun saveProgress() {
        if (played && duration > 0 && pendingResume == 0f) onProgress?.invoke(
            sources.getOrNull(sourceIndex)?.name.orEmpty(), episodes.getOrNull(episodeIndex)?.name.orEmpty(),
            (position.toFloat() / duration).coerceIn(0f, 1f))
    }

    if (showSourcePicker || showEpisodePicker) {
        val choosingSource = showSourcePicker
        AlertDialog(
            modifier = Modifier.tvFocusBoundary(),
            onDismissRequest = { showSourcePicker = false; showEpisodePicker = false },
            title = { Text(if (choosingSource) "切换线路" else "选择剧集") },
            text = {
                LazyColumn(Modifier.height(300.dp)) {
                    if (choosingSource) itemsIndexed(sources) { index, source ->
                        PickerOption(label = (if (index == sourceIndex) "✓ " else "") + source.name,
                            modifier = if (index == 0) Modifier.focusRequester(pickerFocusRequester) else Modifier, onClick = {
                            if (index != sourceIndex) {
                                saveProgress()
                                val episodeName = episodes.getOrNull(episodeIndex)?.name
                                val match = sources[index].episodes.indexOfFirst { it.name == episodeName }
                                val nextProgress = if (match >= 0 && played && duration > 0) (position.toFloat() / duration).coerceIn(0f, 0.98f) else 0f
                                sourceIndex = index
                                episodeIndex = if (match >= 0) match else 0
                                pendingResume = nextProgress
                                played = false
                            }
                            showSourcePicker = false
                        })
                    } else itemsIndexed(episodes) { index, episode ->
                        PickerOption(label = (if (index == episodeIndex) "✓ " else "") + episode.name,
                            modifier = if (index == 0) Modifier.focusRequester(pickerFocusRequester) else Modifier, onClick = {
                            if (index != episodeIndex) { saveProgress(); pendingResume = 0f; played = false; episodeIndex = index }
                            showEpisodePicker = false
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSourcePicker = false; showEpisodePicker = false }) { Text("关闭") } },
            containerColor = XColors.SurfaceRaised,
        )
    }

    fun canSeek(): Boolean = !request.isLive && !showCast && playError == null &&
        player.isCurrentMediaItemSeekable && player.duration > 0

    fun commitSeek(target: Long) {
        player.seekTo(target)
        position = target
        interactionTick++
        seeking = false
        previewTick++
    }

    fun seekBy(seconds: Int) {
        if (!canSeek()) return
        val start = player.currentPosition.coerceIn(0L, player.duration)
        val target = (start + seconds * 1000L).coerceIn(0L, player.duration)
        seekPreview = SeekPreview(start, target, player.duration)
        commitSeek(target)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(XColors.VideoBackground)
            .focusRequester(playerFocusRequester)
            .onFocusChanged { playerHasFocus = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (showCast || playError != null || showSourcePicker || showEpisodePicker) return@onPreviewKeyEvent false
                val directionKey = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                val repeatOnControls = directionKey && seekingKey == event.key && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0
                val finishingSeek = directionKey && event.type == KeyEventType.KeyUp && seekingKey == event.key
                if (!playerHasFocus && !repeatOnControls && !finishingSeek) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (!canSeek()) return@onPreviewKeyEvent true
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (event.nativeKeyEvent.repeatCount > 0 && seekingKey == event.key &&
                                    event.nativeKeyEvent.eventTime - lastSeekKeyTime < 180) return@onPreviewKeyEvent true
                                seekingKey = event.key
                                lastSeekKeyTime = event.nativeKeyEvent.eventTime
                                val start = if (seeking) seekPreview?.start ?: player.currentPosition else player.currentPosition
                                val current = if (seeking) seekPreview?.target ?: start else start
                                val direction = if (event.key == Key.DirectionRight) 1 else -1
                                val target = remoteSeekTarget(current, direction, event.nativeKeyEvent.repeatCount, player.duration)
                                seekPreview = SeekPreview(start, target, player.duration)
                                seeking = true
                                interactionTick++
                            }
                            KeyEventType.KeyUp -> {
                                if (seeking && seekingKey == event.key) seekPreview?.let { commitSeek(it.target) }
                                seekingKey = null
                            }
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyUp) {
                            controlsVisible = true
                            interactionTick++
                            focusControls = true
                            runCatching { playFocusRequester.requestFocus() }
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyUp) {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                            interactionTick++
                            focusControls = true
                            runCatching { playFocusRequester.requestFocus() }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 触摸层：点一下切换控制条（触摸端没有焦点系统，靠这层呼出/收起）。
        // 控制条绘制在它之上，点在按钮上不会穿透到这层。
        Box(
            Modifier
                .fillMaxSize()
                // 这是触摸手势层，不应成为电视遥控器的焦点目标。
                .focusProperties { canFocus = false }
                .pointerInput(player, request.isLive, rawUrl, showCast, playError) {
                    var start = 0L
                    var totalDrag = 0f
                    var validDrag = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            validDrag = canSeek()
                            if (validDrag) {
                                start = player.currentPosition.coerceIn(0L, player.duration)
                                totalDrag = 0f
                                seeking = true
                                seekPreview = SeekPreview(start, start, player.duration)
                                interactionTick++
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            if (validDrag && canSeek()) {
                                totalDrag += amount
                                seekPreview = SeekPreview(start, swipeSeekTarget(start, totalDrag, size.width, player.duration), player.duration)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (validDrag && canSeek()) seekPreview?.let { commitSeek(it.target) }
                            else { seeking = false; seekPreview = null }
                            validDrag = false
                        },
                        onDragCancel = { seeking = false; seekPreview = null; validDrag = false },
                    )
                }
                .clickable {
                    interactionTick++
                    controlsVisible = !controlsVisible
                },
        )

        if (resolving && playError == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = XColors.Accent)
                Spacer(Modifier.height(12.dp))
                Text("正在准备播放", color = XColors.TextPrimary)
            }
        }

        if (playError != null) {
            Box(Modifier.fillMaxSize().background(XColors.VideoBackground.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        playError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = XColors.TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { playError = null; retryKey++ }) { Text("重试") }
                        if (sources.size > 1) TextButton(onClick = { showSourcePicker = true }) { Text("切换线路") }
                    }
                    FocusableSurface(onClick = onClose, modifier = Modifier.focusRequester(errorFocusRequester), shape = RoundedCornerShape(XRadii.pill)) {
                        Box(
                            Modifier
                                .background(XColors.SurfaceRaised, RoundedCornerShape(XRadii.pill))
                                .padding(horizontal = 22.dp, vertical = 10.dp),
                        ) {
                            Text("返回", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        if (seekPreview != null && playError == null && !showCast) {
            SeekOverlay(seekPreview!!, Modifier.align(Alignment.Center))
        }

        if (controlsVisible) {
            TopBar(
                title = request.title,
                subtitle = request.channel?.group ?: episodes.getOrNull(episodeIndex)?.name.orEmpty(),
                onBack = onClose,
                // 焦点在控制条内移动时重置自动收起倒计时，避免导航到一半突然消失
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onFocusChanged { if (it.hasFocus) interactionTick++ },
            )
            BottomBar(
                position = seekPreview?.target ?: position,
                duration = seekPreview?.duration ?: duration,
                isPlaying = isPlaying,
                isLive = request.isLive,
                onSeek = { seekBy(-10) },
                onSeekForward = { seekBy(10) },
                onTogglePlay = {
                    if (player.isPlaying) player.pause() else player.play()
                    controlsVisible = true
                    interactionTick++
                },
                onPrevious = if (episodes.isNotEmpty() && episodeIndex > 0) {
                    {
                        saveProgress()
                        pendingResume = 0f
                        played = false
                        episodeIndex -= 1
                        onEpisodeChange?.invoke(episodeIndex)
                    }
                } else null,
                onNext = if (episodes.isNotEmpty() && episodeIndex < episodes.size - 1) {
                    {
                        saveProgress()
                        pendingResume = 0f
                        played = false
                        episodeIndex += 1
                        onEpisodeChange?.invoke(episodeIndex)
                    }
                } else null,
                onCast = if (url?.startsWith("http://", true) == true || url?.startsWith("https://", true) == true) ({ showCast = true }) else null,
                onChooseSource = if (sources.size > 1) ({ showSourcePicker = true }) else null,
                onChooseEpisode = if (episodes.size > 1) ({ showEpisodePicker = true }) else null,
                focusRequester = playFocusRequester,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onFocusChanged { if (it.hasFocus) interactionTick++ },
            )
        }
        if (showCast && resolved != null) {
            CastControls(
                url = resolved!!.url,
                hasHeaders = resolved!!.headers.isNotEmpty(),
                initialPosition = player.currentPosition.coerceAtLeast(0),
                isLive = request.isLive,
                onStarted = { player.pause() },
                onFinished = { castPosition ->
                    if (!request.isLive && castPosition > 0) player.seekTo(castPosition)
                    player.play()
                },
                onDismiss = { showCast = false },
            )
        }
    }
}

private data class SeekPreview(val start: Long, val target: Long, val duration: Long)

@Composable
private fun SeekOverlay(preview: SeekPreview, modifier: Modifier = Modifier) {
    val delta = (preview.target - preview.start) / 1000
    Column(
        modifier = modifier.background(Color(0xD90B0E14), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (delta >= 0) "快进 +${delta}秒" else "快退 ${-delta}秒", color = XColors.TextPrimary)
        Text("${format(preview.target)} / ${format(preview.duration)}", color = XColors.TextSecondary)
    }
}

@Composable
private fun PickerOption(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(XRadii.md), focusScale = 1.01f) {
        Text(label, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), color = XColors.TextPrimary)
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 状态栏内边距加在背景之后：半透明背景延伸到状态栏底下，内容不被状态栏压住
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC0B0E14))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FocusableSurface(onClick = onBack, shape = CircleShape, focusScale = 1.06f) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = XColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    isLive: Boolean,
    onSeek: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onCast: (() -> Unit)?,
    onChooseSource: (() -> Unit)?,
    onChooseEpisode: (() -> Unit)?,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xD90B0E14))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column {
            if (!isLive && duration > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(XColors.Bar, RoundedCornerShape(2.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((position.toFloat() / duration.toFloat()).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(XColors.Accent, RoundedCornerShape(2.dp)),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(format(position), style = MaterialTheme.typography.bodySmall)
                    Text("-${format(duration - position)}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onPrevious != null) {
                    PlayerIcon(Icons.Outlined.SkipPrevious, "上一集", onPrevious, primary = false)
                }
                if (!isLive) {
                    PlayerIcon(Icons.Outlined.Replay10, "快退 10 秒", onSeek, primary = false)
                }
                PlayerIcon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "暂停" else "播放",
                    onTogglePlay,
                    primary = true,
                    modifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                )
                if (!isLive) {
                    PlayerIcon(Icons.Outlined.Forward10, "快进 10 秒", onSeekForward, primary = false)
                }
                if (onNext != null) {
                    PlayerIcon(Icons.Outlined.SkipNext, "下一集", onNext, primary = false)
                }
                if (onCast != null) TextButton(onClick = onCast) { Text("投屏", color = XColors.TextPrimary) }
                if (onChooseSource != null) TextButton(onClick = onChooseSource) { Text("线路", color = XColors.TextPrimary) }
                if (onChooseEpisode != null) TextButton(onClick = onChooseEpisode) { Text("选集", color = XColors.TextPrimary) }
            }
        }
    }
}

@Composable
private fun PlayerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        focusScale = 1.08f,
    ) {
        Box(
            modifier = Modifier
                .size(if (primary) 44.dp else 40.dp)
                .background(
                    if (primary) XColors.Accent else XColors.SurfaceRaised,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (primary) XColors.OnAccent else XColors.TextPrimary,
                modifier = Modifier.size(if (primary) 22.dp else 18.dp),
            )
        }
    }
}

/**
 * 构造 ExoPlayer 的 MediaItem。
 *
 * 根据地址选择初始格式，无法识别时由播放错误处理尝试另一种格式。
 */
private fun initialHls(url: String, isLive: Boolean): Boolean {
    val path = android.net.Uri.parse(url).path.orEmpty().lowercase()
    return when {
        path.endsWith(".m3u8") -> true
        listOf(".ts", ".flv", ".mp4", ".mkv", ".mpd").any { path.endsWith(it) } -> false
        else -> isLive
    }
}

private fun buildMediaItem(url: String, isHls: Boolean): ExoMediaItem {
    val builder = ExoMediaItem.Builder().setUri(url)
    val mimeType = if (isHls) MimeTypes.APPLICATION_M3U8 else null
    if (mimeType != null) {
        builder.setMimeType(mimeType)
    }
    return builder.build()
}

private fun format(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
