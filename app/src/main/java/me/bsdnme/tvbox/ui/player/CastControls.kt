package me.bsdnme.tvbox.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.bsdnme.tvbox.cast.CastDevice
import me.bsdnme.tvbox.cast.Dlna
import me.bsdnme.tvbox.core.theme.XColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Returns to local playback when the cast session ends. */
@Composable
internal fun CastControls(
    url: String,
    hasHeaders: Boolean,
    initialPosition: Long,
    isLive: Boolean,
    onStarted: () -> Unit,
    onFinished: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mutex = remember { Mutex() }
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var searchKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<CastDevice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var castPosition by remember { mutableLongStateOf(initialPosition) }
    var duration by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    val latestDevice by rememberUpdatedState(device)

    LaunchedEffect(searchKey) {
        searching = true
        error = null
        try { devices = Dlna.discover(context) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "搜索失败：${e.message ?: "请检查网络"}" }
        finally { searching = false }
    }
    // A session belongs to this screen. Stop the TV on exit even if its coroutine scope is cancelled.
    DisposableEffect(Unit) {
        onDispose {
            latestDevice?.let { target ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    mutex.withLock { runCatching { Dlna.action(target, "Stop") } }
                }
            }
        }
    }
    LaunchedEffect(device) {
        val target = device ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            try {
                val position = mutex.withLock { Dlna.position(target) }
                if (position.duration > 0) duration = position.duration
                castPosition = position.position
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun command(action: suspend (CastDevice) -> Unit) {
        val target = device ?: return
        if (busy) return
        scope.launch {
            busy = true
            try { mutex.withLock { action(target) }; error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "操作失败：${e.message ?: "请检查电视连接"}" }
            finally { busy = false }
        }
    }
    fun close() {
        if (!busy) {
            if (device != null) onFinished(castPosition)
            onDismiss()
        }
    }
    BackHandler { close() }

    if (device == null) {
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("选择投屏设备") },
            text = {
                Column {
                    Text("请将手机和电视连接到同一个 Wi-Fi，并在电视上开启 DLNA 投屏。")
                    if (hasHeaders) Text("此视频需要特殊请求头，电视可能无法直接播放。", color = XColors.Accent)
                    Spacer(Modifier.height(12.dp))
                    if (searching) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("正在搜索设备…")
                    } else if (devices.isEmpty()) Text("没有发现设备，可检查电视投屏设置后重试。")
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(devices, key = { it.controlUrl }) { target ->
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    try {
                                        mutex.withLock { Dlna.start(target, url, if (isLive) 0 else initialPosition) }
                                        device = target
                                        onStarted()
                                    } catch (e: CancellationException) {
                                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                            mutex.withLock { runCatching { Dlna.action(target, "Stop") } }
                                        }
                                        throw e
                                    } catch (e: Exception) {
                                        error = "连接失败：${e.message ?: "请检查电视"}"
                                        scope.launch { mutex.withLock { runCatching { Dlna.action(target, "Stop") } } }
                                    } finally { busy = false }
                                }
                            }) { Text(if (busy) "正在连接…" else target.name) }
                        }
                    }
                    error?.let { Text(it, color = XColors.Accent) }
                }
            },
            confirmButton = { TextButton(enabled = !busy && !searching, onClick = { searchKey++ }) { Text("重新搜索") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { close() }) { Text("取消") } },
        )
    } else {
        Box(Modifier.fillMaxSize().background(XColors.VideoBackground), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("正在投屏到 ${device?.name}", color = XColors.TextPrimary)
                if (!isLive && duration > 0) Text("${castTime(castPosition)} / ${castTime(duration)}", color = XColors.TextSecondary)
                error?.let { Text(it, color = XColors.Accent) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isLive) TextButton(enabled = !busy, onClick = { command { Dlna.seek(it, (castPosition - 10_000).coerceAtLeast(0)); castPosition = (castPosition - 10_000).coerceAtLeast(0) } }) { Text("快退 10 秒") }
                    Button(enabled = !busy, onClick = { command { Dlna.action(it, if (playing) "Pause" else "Play", if (playing) emptyMap() else mapOf("Speed" to "1")); playing = !playing } }) { Text(if (playing) "暂停" else "播放") }
                    if (!isLive) TextButton(enabled = !busy, onClick = { command { Dlna.seek(it, castPosition + 10_000); castPosition += 10_000 } }) { Text("快进 10 秒") }
                }
                TextButton(enabled = !busy, onClick = { close() }) { Text("结束投屏，返回手机播放") }
            }
        }
    }
}

private fun castTime(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
