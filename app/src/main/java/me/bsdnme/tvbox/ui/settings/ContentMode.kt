package me.bsdnme.tvbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import me.bsdnme.tvbox.ui.component.TvTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.bsdnme.tvbox.data.ContentAuthorization
import me.bsdnme.tvbox.ui.component.tvFocusBoundary

/** Numeric buttons also work with TV remotes without a software keyboard. */
@Composable
fun ContentAuthorizationDialog(onDismiss: () -> Unit, onAuthorized: () -> Unit) {
    val context = LocalContext.current
    val authorization = remember { ContentAuthorization(context.applicationContext) }
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.tvFocusBoundary(),
        onDismissRequest = onDismiss,
        title = { Text("输入授权码") },
        text = {
            Column {
                Text(if (digits.isEmpty()) "请输入 8 位数字授权码" else "●".repeat(digits.length))
                if (error.isNotEmpty()) Text(error)
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("清空", "0", "删除")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { key -> TextButton(onClick = {
                            digits = when (key) { "清空" -> ""; "删除" -> digits.dropLast(1); else -> if (digits.length < 8) digits + key else digits }
                            error = ""
                        }) { Text(key) } }
                    }
                }
                Text("授权成功后本机无需再次输入；清除应用数据或卸载后需要重新授权。")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (System.currentTimeMillis() < authorization.lockedUntil) {
                    error = "尝试次数过多，请稍后再试"
                } else if (digits.length != 8) {
                    error = "请输入 8 位数字授权码"
                } else if (authorization.authorize(digits)) {
                    onAuthorized()
                } else {
                    digits = ""
                    error = if (System.currentTimeMillis() < authorization.lockedUntil) "尝试次数过多，请 30 秒后再试" else "授权码不正确"
                }
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
