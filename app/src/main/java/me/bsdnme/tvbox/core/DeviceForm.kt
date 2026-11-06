package me.bsdnme.tvbox.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import me.bsdnme.tvbox.core.theme.FormFactor

/**
 * 设备形态判定。
 *
 * 判定顺序：
 * 1. 用户在设置里强制电视模式（方便在手机 / 模拟器上调试遥控器布局）
 * 2. 系统能力上报：UiModeManager == TV、或声明了 LEANBACK / TELEVISION 特性
 * 3. 屏幕最短边 >= 600dp 视为平板，否则手机
 *
 * 为什么不用宽高比猜电视：Android TV 常见 960x540dp，与横屏手机尺寸完全一致，
 * 尺寸判断必然把电视误判成手机，只能靠系统能力上报。
 */
object DeviceForm {

    private const val TABLET_SHORTEST_SIDE_DP = 600

    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        // FEATURE_TELEVISION 在新 SDK 里标记为 deprecated，但部分老盒子只会声明这一项，
        // 所以保留检测，仅屏蔽编译警告
        @Suppress("DEPRECATION")
        val hasTvFeature = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        return hasTvFeature
    }

    fun resolve(context: Context, forceTelevision: Boolean = false): FormFactor {
        if (forceTelevision || isTelevision(context)) {
            return FormFactor.Television
        }
        val config = context.resources.configuration
        val shortest = minOf(config.screenWidthDp, config.screenHeightDp)
        return if (shortest >= TABLET_SHORTEST_SIDE_DP) FormFactor.Tablet else FormFactor.Phone
    }
}
