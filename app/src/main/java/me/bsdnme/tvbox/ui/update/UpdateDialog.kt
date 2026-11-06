package me.bsdnme.tvbox.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.data.net.UpdateInfo

/**
 * 更新弹窗 Host。
 *
 * 挂在 MainActivity 组合树的最上层（FeigeMediaApp 之后），
 * 监听 [UpdateCenter.uiState] 决定渲染哪种弹窗：
 * - Available：新版本说明 + changelog；mandatory 时去掉「以后再说」且点外部不关闭
 * - Downloading：进度条 + 百分比 + 取消
 * - Failed：失败原因 + 重试
 * - Checking：手动检查时的过渡态
 *
 * 电视端焦点：AlertDialog 默认把焦点给 confirmButton（「立即更新」在最前），
 * 遥控器 OK 直接进下载，无需额外处理。
 */
@Composable
fun UpdateDialogHost() {
    val context = LocalContext.current

    when (val state = UpdateCenter.uiState) {
        UpdateUiState.Hidden -> Unit

        UpdateUiState.Checking -> {
            AlertDialog(
                onDismissRequest = { UpdateCenter.dismiss(context) },
                containerColor = XColors.SurfaceRaised,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = XColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("  正在检查更新…", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { UpdateCenter.dismiss(context) }) { Text("取消") }
                },
            )
        }

        is UpdateUiState.Available -> {
            AlertDialog(
                onDismissRequest = { if (!state.mandatory) UpdateCenter.dismiss(context) },
                containerColor = XColors.SurfaceRaised,
                title = {
                    Text(
                        "发现新版本 v${state.info.versionName}",
                        fontWeight = FontWeight.Bold,
                        color = XColors.TextPrimary,
                    )
                },
                text = {
                    Column {
                        if (state.mandatory) {
                            Text(
                                "此版本必须更新后才能继续使用",
                                style = MaterialTheme.typography.bodySmall,
                                color = XColors.Danger,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (state.info.changelog.isNotBlank()) {
                            Text(
                                state.info.changelog,
                                style = MaterialTheme.typography.bodyMedium,
                                color = XColors.TextSecondary,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        UpdateCenter.startDownload(context, state.info, state.mandatory)
                    }) { Text("立即更新", color = XColors.Accent) }
                },
                dismissButton = {
                    if (!state.mandatory) {
                        TextButton(onClick = { UpdateCenter.dismiss(context) }) { Text("以后再说") }
                    }
                },
            )
        }

        is UpdateUiState.Downloading -> {
            val percent = (state.progress * 100).toInt()
            AlertDialog(
                onDismissRequest = { /* 下载中不允许点外部关闭，用取消按钮 */ },
                containerColor = XColors.SurfaceRaised,
                title = {
                    Text("正在下载更新", fontWeight = FontWeight.Bold, color = XColors.TextPrimary)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = XColors.Accent,
                            trackColor = XColors.Bar,
                        )
                        Text(
                            "$percent% · 下载完成后会自动打开安装界面",
                            style = MaterialTheme.typography.bodySmall,
                            color = XColors.TextTertiary,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { UpdateCenter.cancelDownload(context) }) { Text("取消") }
                },
            )
        }

        is UpdateUiState.Failed -> {
            AlertDialog(
                onDismissRequest = { if (!state.mandatory) UpdateCenter.dismiss(context) },
                containerColor = XColors.SurfaceRaised,
                title = {
                    Text("更新失败", fontWeight = FontWeight.Bold, color = XColors.TextPrimary)
                },
                text = {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = XColors.TextSecondary)
                },
                confirmButton = {
                    TextButton(onClick = {
                        UpdateCenter.startDownload(context, state.info, state.mandatory)
                    }) { Text("重试", color = XColors.Accent) }
                },
                dismissButton = {
                    if (!state.mandatory) {
                        TextButton(onClick = { UpdateCenter.dismiss(context) }) { Text("关闭") }
                    }
                },
            )
        }
    }
}
