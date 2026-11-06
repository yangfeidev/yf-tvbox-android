package me.bsdnme.tvbox.ui.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/** 接收系统安装会话的确认请求和最终结果。 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    requireNotNull(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                } catch (e: Exception) {
                    Log.e("UpdateCenter", "Unable to open install confirmation", e)
                    UpdateCenter.onInstallFailure("无法打开安装确认页面，请重试")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.d("UpdateCenter", "Update installed")
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e("UpdateCenter", "Install failed: status=$status, detail=$detail")
                val message = "安装失败${detail?.let { "：$it" } ?: "，请重试"}"
                UpdateCenter.onInstallFailure(message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
