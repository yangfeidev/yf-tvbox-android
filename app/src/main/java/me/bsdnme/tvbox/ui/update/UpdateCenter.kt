package me.bsdnme.tvbox.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bsdnme.tvbox.data.net.UpdateApi
import me.bsdnme.tvbox.data.net.UpdateInfo
import java.io.File

/**
 * 更新弹窗状态机。
 *
 * Hidden      静默，不渲染任何东西
 * Checking    手动检查中（启动静默检查不进这个态，失败也不打扰）
 * Available   有新版本，mandatory=true 时不可关闭
 * Downloading 下载中，progress 0..1
 * Failed      下载失败，可重试；mandatory=true 时不可关闭
 */
sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val info: UpdateInfo, val mandatory: Boolean) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUiState
    data class Failed(val message: String, val info: UpdateInfo, val mandatory: Boolean) : UpdateUiState
}

/**
 * 更新流程的全局控制器。
 *
 * 用单例 + Compose State 而不是塞进 AppViewModel：
 * 检查更新的入口有两处（MainActivity 启动静默、设置页手动），
 * 而弹窗必须悬浮在整个 App 之上，放 ViewModel 里需要层层透传，
 * 单例让三端共用一个状态源，弹窗只在 MainActivity 顶部挂一个 Host。
 *
 * 网络都在自建的 IO scope 里跑，状态写回是 Compose 快照写，线程安全。
 */
object UpdateCenter {

    var uiState by mutableStateOf<UpdateUiState>(UpdateUiState.Hidden)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private data class DownloadedApk(val info: UpdateInfo, val file: File, val mandatory: Boolean)
    @Volatile private var downloadedApk: DownloadedApk? = null
    private var awaitingInstallPermission = false
    private val downloadMutex = Mutex()
    @Volatile private var downloadGeneration = 0

    // ------------------------------------------------------------------ 当天免打扰
    //
    // 只要用户表达过「现在不更」（点以后再说 / 关弹窗 / 取消下载 / 已拉起安装器），
    // 当天静默检查就不再弹这个版本，第二天才重新提醒。
    // 手动检查（设置页）不看这个标记：用户主动查就该有结果。

    private fun remindPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("feigemedia_update", Context.MODE_PRIVATE)

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun isRemindSuppressed(context: Context, versionCode: Int): Boolean {
        val prefs = remindPrefs(context)
        return prefs.getInt(KEY_REMIND_VERSION, -1) == versionCode &&
            prefs.getString(KEY_REMIND_DATE, null) == today()
    }

    private fun markRemindLater(context: Context, versionCode: Int) {
        remindPrefs(context).edit()
            .putInt(KEY_REMIND_VERSION, versionCode)
            .putString(KEY_REMIND_DATE, today())
            .apply()
    }

    private const val KEY_REMIND_VERSION = "remind_version"
    private const val KEY_REMIND_DATE = "remind_date"

    fun currentVersionCode(context: Context): Int {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun currentVersionName(context: Context): String {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        return pi.versionName ?: ""
    }

    /** 是否强制更新：软强更标记，或已安装版本低于 minVersionCode */
    private fun mandatory(current: Int, info: UpdateInfo): Boolean =
        info.force || current < info.minVersionCode

    /**
     * 启动静默检查：任何失败都不打扰用户，有新版本才弹窗。
     * MainActivity 在品牌闪屏之后延迟调用。
     */
    fun silentCheck(context: Context) {
        scope.launch {
            runCatching {
                val info = UpdateApi.check()
                val current = currentVersionCode(context)
                if (info.versionCode > current && !isRemindSuppressed(context, info.versionCode)) {
                    uiState = UpdateUiState.Available(info, mandatory(current, info))
                }
            }
        }
    }

    /**
     * 设置页手动检查：结果以文案返回给调用方展示。
     * 返回 null 表示发现新版本（弹窗接管），非 null 是「已是最新 / 失败原因」。
     */
    suspend fun manualCheck(context: Context): String? {
        uiState = UpdateUiState.Checking
        return try {
            val info = withContext(Dispatchers.IO) { UpdateApi.check() }
            val current = currentVersionCode(context)
            if (info.versionCode > current) {
                uiState = UpdateUiState.Available(info, mandatory(current, info))
                null
            } else {
                uiState = UpdateUiState.Hidden
                "已是最新版本 v${currentVersionName(context)}"
            }
        } catch (e: Exception) {
            uiState = UpdateUiState.Hidden
            "检查失败：${e.message ?: "网络异常"}"
        }
    }

    /** 开始下载，下载完成后自动拉起系统安装器 */
    fun startDownload(context: Context, info: UpdateInfo, mandatory: Boolean) {
        downloadJob?.cancel()
        val generation = ++downloadGeneration
        uiState = UpdateUiState.Downloading(info, 0f)
        downloadJob = scope.launch {
            try {
                val file = downloadMutex.withLock {
                    val cached = downloadedApk?.takeIf {
                        it.info.versionCode == info.versionCode && it.info.apkUrl == info.apkUrl &&
                            it.file.isFile && it.file.length() > 0
                    }
                    cached?.file ?: run {
                        downloadedApk = null
                        UpdateApi.download(context.applicationContext, info) { progress ->
                            ensureActive()
                            val state = uiState
                            if (generation == downloadGeneration && state is UpdateUiState.Downloading) {
                                uiState = state.copy(progress = progress)
                            }
                        }
                    }
                }
                ensureActive()
                withContext(Dispatchers.Main) {
                    downloadedApk = DownloadedApk(info, file, mandatory)
                    // 已拉起安装器 = 用户在处理更新了，当天不再重复提醒
                    // （安装器里取消或没装完就切回来，也不会再弹）
                    markRemindLater(context, info.versionCode)
                    uiState = UpdateUiState.Hidden
                    install(context, file)
                }
            } catch (e: CancellationException) {
                if (generation == downloadGeneration) uiState = UpdateUiState.Hidden
            } catch (e: Exception) {
                if (generation == downloadGeneration) uiState = UpdateUiState.Failed(e.message ?: "下载失败", info, mandatory)
            }
        }
    }

    /** 用户取消下载：同样算「今天不想更」，当天静默检查免打扰 */
    fun cancelDownload(context: Context) {
        val state = uiState
        if (state is UpdateUiState.Downloading) markRemindLater(context, state.info.versionCode)
        downloadGeneration++
        downloadJob?.cancel()
        downloadJob = null
        uiState = UpdateUiState.Hidden
    }

    /**
     * 用户关闭弹窗（以后再说 / 关闭 / 点外部）：
     * 记录「当天不再提醒该版本」，第二天静默检查才重新弹。
     */
    fun dismiss(context: Context) {
        val state = uiState
        val version = when (state) {
            is UpdateUiState.Available -> state.info.versionCode
            is UpdateUiState.Failed -> state.info.versionCode
            else -> null
        }
        version?.let { markRemindLater(context, it) }
        uiState = UpdateUiState.Hidden
    }

    /**
     * 拉起系统安装器。
     *
     * API 26+ 需要「允许安装未知应用」授权：没有授权时先跳系统设置页，
     * 用户返回后继续安装已完成下载的 APK。
     */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            awaitingInstallPermission = true
            runCatching { context.startActivity(settingsIntent) }
                .onFailure { awaitingInstallPermission = false; context.startActivity(installIntent) }
        } else {
            context.startActivity(installIntent)
        }
    }

    fun onResume(context: Context) {
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        val downloaded = downloadedApk ?: return
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) {
            if (downloaded.file.isFile && downloaded.file.length() > 0) install(context, downloaded.file)
            else uiState = UpdateUiState.Available(downloaded.info, downloaded.mandatory)
        } else {
            uiState = UpdateUiState.Available(downloaded.info, downloaded.mandatory)
        }
    }
}
