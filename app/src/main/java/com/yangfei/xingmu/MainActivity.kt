package com.yangfei.xingmu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yangfei.xingmu.core.DeviceForm
import com.yangfei.xingmu.core.theme.XingmuTheme
import com.yangfei.xingmu.ui.XingmuApp
import com.yangfei.xingmu.vm.AppViewModel

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

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.yangfei.xingmu.R.style.Theme_Xingmu)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))

        setContent {
            val vm: AppViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val forceTelevision = state.settings.forceTelevision
            val formFactor = remember(forceTelevision) {
                DeviceForm.resolve(this, forceTelevision)
            }
            XingmuTheme(formFactor = formFactor) {
                XingmuApp(formFactor = formFactor)
            }
        }
    }
}
