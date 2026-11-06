package me.bsdnme.tvbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.bsdnme.tvbox.core.DeviceForm
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.XColors
import me.bsdnme.tvbox.ui.component.CreatorSignature
import kotlinx.coroutines.delay
import me.bsdnme.tvbox.ui.FeigeMediaApp
import me.bsdnme.tvbox.ui.update.UpdateCenter
import me.bsdnme.tvbox.ui.update.UpdateDialogHost
import me.bsdnme.tvbox.vm.AppViewModel

/**
 * 唯一 Activity。
 *
 * 手机、平板、电视共用一个 Activity，形态在进入 Compose 之前算好，
 * 通过 CompositionLocal 下发给整棵树。
 * AndroidManifest 里声明了 uiMode / screenSize 的 configChanges，
 * 避免横竖屏或 TV 模式切换时重建导致播放中断。
 *
 * 形态会随「强制电视模式」开关实时变化，所以 resolve 放在 remember(forceTv) 里。
 */
class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        UpdateCenter.onResume(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(me.bsdnme.tvbox.R.style.Theme_FeigeMedia)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))

        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val forceTelevision = state.settings.forceTelevision
            val formFactor = remember(forceTelevision) {
                DeviceForm.resolve(this, forceTelevision)
            }
            FeigeMediaTheme(formFactor = formFactor) {
                var showBrand by remember { mutableStateOf(savedInstanceState == null) }
                LaunchedEffect(Unit) {
                    if (showBrand) {
                        delay(1600)
                        showBrand = false
                    }
                }
                // 启动静默检查更新：放在品牌闪屏之后，失败不打扰，有新版本才弹窗
                LaunchedEffect(Unit) {
                    delay(2500)
                    UpdateCenter.silentCheck(applicationContext)
                }
                Box(Modifier.fillMaxSize()) {
                    if (state.localLoaded) FeigeMediaApp(formFactor = formFactor)
                    if (showBrand || !state.localLoaded) StartupBrand()
                    // 更新弹窗悬浮在所有页面之上，三端共用
                    UpdateDialogHost()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StartupBrand() {
    Box(Modifier.fillMaxSize().background(XColors.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(112.dp).clip(RoundedCornerShape(28.dp)),
            )
            Spacer(Modifier.height(18.dp))
            Text("飞哥影视仓", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = XColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            CreatorSignature(style = MaterialTheme.typography.bodyMedium)
        }
    }
}
