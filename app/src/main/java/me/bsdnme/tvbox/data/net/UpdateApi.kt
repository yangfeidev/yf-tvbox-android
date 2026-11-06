package me.bsdnme.tvbox.data.net

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 检查更新数据层。
 *
 * 版本信息由 Cloudflare R2 托管（dl.bsdnme.me/update.json），
 * APK 本体也放在同一个桶里，R2 免费档流出不计费、无 Pages 单文件 25MB 限制。
 *
 * update.json 结构：
 * ```json
 * {
 *   "versionCode": 2,
 *   "versionName": "1.0.1",
 *   "minVersionCode": 1,
 *   "apkUrl": "https://dl.bsdnme.me/feige-1.0.1.apk",
 *   "changelog": "修复 xxx；新增 xxx",
 *   "force": false
 * }
 * ```
 *
 * 字段语义：
 * - versionCode：唯一可信的版本比较依据（Int），versionName 只用于展示
 * - minVersionCode：已安装版本低于它时强制升级（弹窗不可关闭）
 * - force：软强更标记，为 true 时同样不可关闭
 * - changelog：展示用更新说明，支持多行
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int = 0,
    val apkUrl: String,
    val changelog: String = "",
    val force: Boolean = false,
)

object UpdateApi {

    /**
     * 版本信息地址，发布时通过 wrangler 上传新的 update.json 覆盖。
     * 共享桶 dl-apps 按 App 全拼分目录：dl.bsdnme.me/<appId>/update.json，
     * 新 App 接入时只加前缀，不动域名和桶。
     */
    const val CHECK_URL = "https://dl.bsdnme.me/feige-yingshicang/update.json"

    /** 解析 update.json，字段缺失按默认值兜底，versionCode / apkUrl 缺失视为无效 */
    fun parse(text: String): UpdateInfo {
        val json = JSONObject(text)
        val info = UpdateInfo(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            minVersionCode = json.optInt("minVersionCode", 0),
            apkUrl = json.optString("apkUrl", ""),
            changelog = json.optString("changelog", ""),
            force = json.optBoolean("force", false),
        )
        if (info.versionCode <= 0 || info.apkUrl.isBlank()) {
            throw SourceException("版本信息格式不正确")
        }
        return info
    }

    /** 拉取并解析版本信息，网络失败直接抛 [SourceException]，由上层决定静默还是提示 */
    fun check(): UpdateInfo = parse(Http.get(CHECK_URL))

    /**
     * 下载 APK 到应用外部私有目录 update/，覆盖旧文件。
     *
     * onProgress 回调 0..1 的进度（服务端不返回长度时只在结束时回调 1）。
     * 调用方通过在回调里 ensureActive() 实现取消——取消时流的 read 会抛
     * CancellationException，use 块负责关闭并落盘半个文件（下次下载会先删掉）。
     */
    fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = context.getExternalFilesDir("update") ?: File(context.cacheDir, "update")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "feige-update.apk")
        if (target.exists()) target.delete()

        // 下载专用的客户端：APK 体积大、耗时长，复用 Http 的 15 秒总超时必然中断
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", Http.DEFAULT_UA)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SourceException("HTTP ${response.code}")
                val body = response.body ?: throw SourceException("响应内容为空")
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                }
                if (total <= 0) onProgress(1f)
                return target
            }
        } catch (e: SourceException) {
            throw e
        } catch (e: IOException) {
            throw SourceException("下载失败：${e.message ?: "网络异常"}")
        }
    }
}
