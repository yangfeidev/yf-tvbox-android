package me.bsdnme.tvbox.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 数据层统一异常，UI 直接把 message 展示给用户。 */
class SourceException(message: String) : Exception(message)

/**
 * 网络层。
 *
 * 只做 GET，不做缓存与重试策略——源站本身就不稳定，
 * 重试会把等待时间成倍放大，不如让用户手动换源。
 *
 * UA 默认伪装成移动浏览器：部分 CMS 会拒绝 okhttp 默认 UA。
 */
object Http {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // 整个调用（含建连、发送、接收）的总时长上限。
        // 没有它的话，源站用「每次只吐几个字节然后卡住」的方式响应时，
        // readTimeout 会被不断重置，请求永远不结束，界面就一直转圈。
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
        readTimeoutSeconds: Long = 15,
    ): String {
        if (url.isBlank()) throw SourceException("地址为空")
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "*/*")
        headers.forEach { (key, value) -> builder.header(key, value) }
        try {
            val requestClient = if (readTimeoutSeconds == 15L) client else client.newBuilder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build()
            val call = requestClient.newCall(builder.build())
            // 按接口单独放宽超时：直播聚合地址每次都要现场探测上游，可能几十秒才返回；
            // 点播接口如果 15 秒还没响应，基本可以判定源已失效，早点报错让用户换源。
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw SourceException("HTTP ${response.code}")
                }
                val body = response.body ?: throw SourceException("响应内容为空")
                val text = body.string()
                if (text.isBlank()) throw SourceException("响应内容为空")
                return text
            }
        } catch (e: SourceException) {
            throw e
        } catch (e: IOException) {
            val reason = when {
                e is java.net.SocketTimeoutException ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                    "连接或读取超时，请检查网络后重试"
                else -> e.message ?: e.javaClass.simpleName
            }
            throw SourceException("网络请求失败：$reason")
        } catch (e: IllegalArgumentException) {
            throw SourceException("地址格式不正确")
        }
    }
}
