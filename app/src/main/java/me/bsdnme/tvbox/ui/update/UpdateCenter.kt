package me.bsdnme.tvbox.ui.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
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
import me.bsdnme.tvbox.data.net.SourceException
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

    private const val TAG = "UpdateCenter"

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

    /**
     * 校验下载的 APK 确实是目标版本。
     * 用 PackageManager 解析包信息，与 update.json 里的 versionCode 比对；
     * 不一致就删掉文件并报错，走 Failed 态让用户重试，绝不拉起安装器。
     */
    private fun verifyDownloadedApk(context: Context, file: File, expectedVersionCode: Int) {
        if (!file.isFile || file.length() == 0L) {
            throw SourceException("下载的安装包为空，请重试")
        }
        val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (archiveInfo == null) {
            file.delete()
            throw SourceException("安装包已损坏，请重新下载")
        }
        if (archiveInfo.versionCode != expectedVersionCode) {
            file.delete()
            throw SourceException("安装包版本不正确（预期 v$expectedVersionCode，实际 v${archiveInfo.versionCode}），请重试")
        }
    }

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
                // 下载产物必须是目标版本：防止缓存/覆盖/桶内文件错乱导致装错包，
                // 也让「装完还是旧版」这类问题在下载阶段就暴露，而不是装完说不清
                verifyDownloadedApk(context, file, info.versionCode)
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
     * 拉起安装。
     *
     * 不走 ACTION_VIEW + FileProvider：ColorOS / 电视定制 ROM 的安装器处理
     * content URI 时解析不到传入包的真实版本——回显已安装应用的旧版本号
     * 和旧图标，点安装后还是空跑一遍报「安装完成」，新包根本没进去
     * （2026-11-06 一加 Android 16 真机复现实锤）。
     * 改用系统 PackageInstaller 会话把 APK 流式写入，版本信息由会话内
     * 的真实 APK 提供，不经过第三方安装器的解析链路。
     *
     * API 26+ 需要「允许安装未知应用」授权：没有授权时先跳系统设置页，
     * 用户返回后继续安装已完成下载的 APK（onResume 接管）。
     */
    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            awaitingInstallPermission = true
            runCatching { context.startActivity(settingsIntent) }
                .onFailure { awaitingInstallPermission = false; installViaSession(context, file) }
        } else {
            installViaSession(context, file)
        }
    }

    /** PackageInstaller 会话安装；异常时回退 ACTION_VIEW，保证不比旧行为差 */
    private fun installViaSession(context: Context, file: File) {
        try {
            val installer = context.packageManager.packageInstaller
            // 清理悬死会话：定制 ROM 上 commit 后确认页可能不弹（实测会卡死），
            // 旧会话一直挂在「待确认」会让下次 CONFIRM_INSTALL 顶出坏会话报
            // 「安装包已损坏」，所以每次安装前把自家遗留会话全部作废
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    installer.mySessions.forEach { info ->
                        runCatching { installer.abandonSession(info.sessionId) }
                    }
                }
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // 必须给精确大小：部分定制 ROM（ColorOS 等）在 size 未知时
            // 校验阶段直接卡死，确认页永远不弹，会话悬在 committed 态
            params.setSize(file.length())
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            Log.d(TAG, "install session=$sessionId created, size=${file.length()}")
            try {
                val out = session.openWrite("feige-update", 0, file.length())
                try {
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                } finally {
                    out.close()
                }
                // 系统通过回调提供当前会话的确认 Intent 和最终安装结果。
                val flags = if (Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
                val statusIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallStatusReceiver::class.java),
                    flags,
                )
                session.commit(statusIntent.intentSender)
                Log.d(TAG, "install session=$sessionId committed")
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "session install failed, fallback to view intent", e)
            installViaViewIntent(context, file)
        }
    }

    internal fun onInstallFailure(message: String) {
        val downloaded = downloadedApk ?: return
        uiState = UpdateUiState.Failed(message, downloaded.info, downloaded.mandatory)
    }

    /** 旧方式：ACTION_VIEW + FileProvider，仅作 PackageInstaller 失败时的兜底 */
    private fun installViaViewIntent(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
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
