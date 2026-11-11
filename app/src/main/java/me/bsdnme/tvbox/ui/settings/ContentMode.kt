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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.bsdnme.tvbox.data.FamilyPin
import me.bsdnme.tvbox.ui.component.tvFocusBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Numeric buttons work with TV remotes without relying on a software keyboard. */
@Composable
fun PinDialog(changePin: Boolean, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val pinStore = remember { FamilyPin(context.applicationContext) }
    var stage by remember { mutableStateOf(if (pinStore.isSet) "verify" else "new") }
    var digits by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = when (stage) { "verify" -> if (changePin) "验证当前 PIN" else "输入 PIN，进入个人模式"; "new" -> "设置 4～6 位 PIN"; else -> "再次输入 PIN" }
    AlertDialog(
        modifier = Modifier.tvFocusBoundary(),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(if (digits.isEmpty()) "请输入数字" else "●".repeat(digits.length))
                if (error.isNotEmpty()) Text(error)
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("清空", "0", "删除")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { key -> TextButton(enabled = !busy, onClick = {
                            digits = when (key) { "清空" -> ""; "删除" -> digits.dropLast(1); else -> if (digits.length < 6) digits + key else digits }
                            error = ""
                        }) { Text(key) } }
                    }
                }
                Text("忘记 PIN 需在系统设置中清除应用数据，这会删除本机订阅、收藏和历史。")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (digits.length !in 4..6) { error = "请输入 4～6 位数字"; return@TextButton }
                when (stage) {
                    "new" -> { first = digits; digits = ""; stage = "confirm"; error = "" }
                    "confirm" -> if (digits != first) { digits = ""; first = ""; stage = "new"; error = "两次输入不一致，请重新设置" } else {
                        val value = digits
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { pinStore.set(value) }
                            busy = false
                            if (changePin) onDismiss() else onUnlocked()
                        }
                    }
                    else -> {
                        if (System.currentTimeMillis() < pinStore.lockedUntil) { error = "尝试次数过多，请稍后再试"; return@TextButton }
                        val value = digits
                        busy = true
                        scope.launch {
                            val correct = withContext(Dispatchers.IO) { pinStore.verify(value) }
                            busy = false; digits = ""
                            if (correct) {
                                if (changePin) { stage = "new"; error = "" } else onUnlocked()
                            } else error = if (System.currentTimeMillis() < pinStore.lockedUntil) "尝试次数过多，请 30 秒后再试" else "PIN 不正确"
                        }
                    }
                }
            }) { Text("确认") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
