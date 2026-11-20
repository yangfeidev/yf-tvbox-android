package me.bsdnme.tvbox.data.net

import android.content.Context
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import me.bsdnme.tvbox.data.model.Site
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch

/** Classic Android DEX spiders. Approval is tied to the downloaded bytes, not a URL. */
object SpiderRuntime {
    data class Prepared(val source: String, val sha256: String, val file: File, val configUrl: String) {
        val permissionKey: String get() = MessageDigest.getInstance("SHA-256")
            .digest("$sha256\n$configUrl".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private lateinit var context: Context
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private val prepared = mutableMapOf<String, Prepared>()
    private val loaders = mutableMapOf<String, DexClassLoader>()
    private val spiders = mutableMapOf<Pair<String, Site>, Spider>()
    @Volatile private var remote: IBinder? = null
    private var boundConnection: ServiceConnection? = null

    fun initialize(ctx: Context) { context = ctx.applicationContext }
    private fun prefs() = context.getSharedPreferences("spider_permissions", Context.MODE_PRIVATE)
    private fun digest(bytes: ByteArray, algorithm: String) = MessageDigest.getInstance(algorithm)
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized fun prepare(site: Site, refresh: Boolean = false): Prepared {
        if (!::context.isInitialized) throw SourceException("Spider 运行时尚未初始化")
        if (!site.api.matches(Regex("csp_[A-Za-z0-9_]+"))) throw SourceException("此 type 3 源不是支持的 JAR 类名")
        val cacheKey = site.jar + "\n" + site.configUrl
        if (!refresh) prepared[cacheKey]?.let { return it }
        val parts = site.jar.split(";md5;", limit = 2)
        val url = parts[0].toHttpUrlOrNull() ?: throw SourceException("JAR 地址无效")
        val bytes = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", Http.DEFAULT_UA).build()).execute().use { response ->
                if (!response.isSuccessful) throw SourceException("JAR 下载失败：HTTP ${response.code}")
                val body = response.body ?: throw SourceException("JAR 内容为空")
                if (body.contentLength() > 20L * 1024 * 1024) throw SourceException("JAR 超过大小限制")
                val out = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (out.size() + n > 20 * 1024 * 1024) throw SourceException("JAR 超过大小限制")
                        out.write(buffer, 0, n)
                    }
                }
                out.toByteArray()
            }
        } catch (e: SourceException) { throw e } catch (e: Exception) { throw SourceException("JAR 下载失败：${e.message}") }
        if (parts.size == 2) {
            val expected = parts[1].trim()
            if (!expected.matches(Regex("[a-fA-F0-9]{32}"))) throw SourceException("不支持此 JAR 的 MD5 描述")
            if (!digest(bytes, "MD5").equals(expected, true)) throw SourceException("JAR 的 MD5 校验失败")
        }
        val hash = digest(bytes, "SHA-256")
        val dir = File(context.codeCacheDir, "spiders").also { it.mkdirs() }
        val file = File(dir, "$hash.jar")
        if (!file.exists()) {
            val temp = File.createTempFile("download-", ".jar", dir)
            try {
                temp.outputStream().use { it.write(bytes) }
                ZipFile(temp).use { zip -> if (zip.getEntry("classes.dex") == null) throw SourceException("JAR 不含 Android DEX") }
                if (!temp.setReadOnly() || !temp.renameTo(file)) throw SourceException("无法保存只读 JAR")
            } finally { temp.delete() }
        }
        return Prepared(url.toString(), hash, file, site.configUrl).also { prepared[cacheKey] = it }
    }

    fun approved(jar: Prepared): Boolean = prefs().getBoolean(jar.permissionKey, false)
    fun approve(jar: Prepared) { prefs().edit().putBoolean(jar.permissionKey, true).apply() }
    @Synchronized fun revokeAll() {
        prefs().edit().clear().apply()
        boundConnection?.let { runCatching { context.unbindService(it) } }
        boundConnection = null
        remote = null
    }

    @Synchronized private fun spider(site: Site, jar: Prepared): Spider {
        val identity = jar.sha256 to site
        spiders[identity]?.let { return it }
        try {
            val loader = loaders[jar.sha256] ?: DexClassLoader(jar.file.absolutePath, context.codeCacheDir.absolutePath, null, Spider::class.java.classLoader).also {
                try { it.loadClass("com.github.catvod.spider.Init").getMethod("init", Context::class.java).invoke(null, context) }
                catch (_: ClassNotFoundException) { }
                loaders[jar.sha256] = it
            }
            val instance = loader.loadClass("com.github.catvod.spider." + site.api.removePrefix("csp_")).getDeclaredConstructor().newInstance() as Spider
            instance.init(context, site.ext)
            spiders[identity] = instance
            return instance
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            throw SourceException("Spider 加载失败：${cause.javaClass.simpleName}${cause.message?.let { "：$it" }.orEmpty()}")
        }
    }

    internal fun executeLocal(site: Site, hash: String, operation: String, first: String, second: String): String {
        try {
            if (!hash.matches(Regex("[a-f0-9]{64}"))) throw SourceException("无效的 JAR 指纹")
            val file = File(context.codeCacheDir, "spiders/$hash.jar")
            if (!file.isFile || digest(file.readBytes(), "SHA-256") != hash) throw SourceException("JAR 文件已更改")
            val spider = spider(site, Prepared(site.jar, hash, file, site.configUrl))
            return synchronized(spider) {
                when (operation) {
                    "home" -> spider.homeContent(true)
                    "homeVideo" -> spider.homeVideoContent()
                    "category" -> spider.categoryContent(first, second, true, hashMapOf())
                    "search" -> spider.searchContent(first, false, second)
                    "detail" -> spider.detailContent(listOf(first))
                    "play" -> spider.playerContent(first, second, emptyList())
                    else -> throw SourceException("不支持的 Spider 操作")
                }
            }
        } catch (e: SourceException) { throw e }
        catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            throw SourceException("Spider 执行失败：${cause.javaClass.simpleName}${cause.message?.let { "：$it" }.orEmpty()}")
        }
    }

    @Synchronized private fun connection(): IBinder {
        remote?.takeIf { it.isBinderAlive }?.let { return it }
        boundConnection?.let { runCatching { context.unbindService(it) } }
        boundConnection = null
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { remote = binder; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { remote = null }
            override fun onBindingDied(name: ComponentName?) { remote = null; latch.countDown() }
        }
        if (!context.bindService(Intent(context, SpiderService::class.java), connection, Context.BIND_AUTO_CREATE)) throw SourceException("无法启动 Spider 进程")
        boundConnection = connection
        if (!latch.await(10, TimeUnit.SECONDS)) throw SourceException("Spider 进程启动超时")
        return remote ?: throw SourceException("Spider 进程启动失败")
    }

    fun call(site: Site, operation: String, first: String = "", second: String = ""): String {
        val jar = prepare(site)
        if (!approved(jar)) throw SourceException("第三方 JAR 尚未授权，或文件已更新，请在源站列表重新授权")
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            listOf(site.key, site.name, site.api, site.ext, site.jar, site.configUrl, jar.sha256, operation, first, second).forEach(request::writeString)
            if (!connection().transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0)) throw SourceException("Spider 进程未响应")
            val error = reply.readString().orEmpty()
            if (error.isNotEmpty()) throw SourceException(error)
            return reply.readString().orEmpty()
        } catch (e: SourceException) { throw e }
        catch (e: Exception) { remote = null; throw SourceException("Spider 进程异常退出：${e.message.orEmpty()}") }
        finally { request.recycle(); reply.recycle() }
    }
}
