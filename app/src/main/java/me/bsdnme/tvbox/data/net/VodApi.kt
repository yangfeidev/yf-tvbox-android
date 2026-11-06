package me.bsdnme.tvbox.data.net

import me.bsdnme.tvbox.data.model.Category
import me.bsdnme.tvbox.data.model.MediaDetail
import me.bsdnme.tvbox.data.model.PagedMedia
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.model.MediaItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 源站 API 访问层。
 *
 * 只负责「拼 URL → 发请求 → 交给 [VodParser] 解析」，不含任何 UI 状态。
 *
 * 参数约定（苹果CMS v10）：
 * - 分类列表 `ac=list`
 * - 列表 `ac=videolist&t=<分类>&pg=<页码>`
 * - 搜索 `ac=videolist&wd=<关键词>&pg=<页码>`
 * - 详情 `ac=videolist&ids=<id>`，部分源只认 `ac=detail`，失败后自动重试
 *
 * 为什么分类要单独请求：`ac=videolist` 不会返回 `class` 节点，
 * 只有 `ac=list` 才带分类表，混用会拿到空分类。
 */
object VodApi {

    fun categories(site: Site): List<Category> {
        if (site.type == 4) return VodParser.parseList(Http.get(buildUrl(site.api, hashMapOf("filter" to "true", "extend" to site.ext))), true).categories
        val params = baseParams(site)
        params["ac"] = "list"
        val body = Http.get(buildUrl(site.api, params))
        return VodParser.parseList(body, site.isJsonApi).categories
    }

    fun list(site: Site, typeId: String = "", page: Int = 1): PagedMedia {
        if (site.type == 4) {
            val params = if (typeId.isBlank()) hashMapOf("filter" to "true", "extend" to site.ext) else hashMapOf("ac" to "detail", "filter" to "true", "t" to typeId, "pg" to page.toString(), "ext" to "")
            val result = normalizeImages(site, VodParser.parseList(Http.get(buildUrl(site.api, params)), true))
            if (typeId.isBlank() && result.items.isEmpty() && result.categories.isNotEmpty()) {
                val first = list(site, result.categories.first().typeId, 1)
                return first.copy(categories = result.categories)
            }
            return result
        }
        val params = baseParams(site)
        params["ac"] = if (site.isJsonApi) "detail" else "videolist"
        params["pg"] = page.toString()
        if (typeId.isNotBlank()) params["t"] = typeId
        val body = Http.get(buildUrl(site.api, params))
        return normalizeImages(site, VodParser.parseList(body, site.isJsonApi))
    }

    /** 有些 CMS 的父分类不含视频，仅子分类有内容。 */
    suspend fun listChildren(site: Site, parentId: String, categories: List<Category>, page: Int): PagedMedia {
        val descendants = mutableListOf<Category>()
        val visited = mutableSetOf(parentId)
        fun collect(id: String) {
            categories.filter { it.parentId == id && visited.add(it.typeId) }.forEach {
                descendants.add(it)
                collect(it.typeId)
            }
        }
        collect(parentId)
        if (descendants.isEmpty()) return PagedMedia(page = page)
        val pages = coroutineScope {
            descendants.chunked(4).flatMap { batch ->
                batch.map { category -> async(Dispatchers.IO) { list(site, category.typeId, page) } }.awaitAll()
            }
        }
        return PagedMedia(
            items = pages.flatMap { it.items }.distinctBy { it.id },
            page = page,
            pageCount = pages.maxOfOrNull { it.pageCount } ?: 1,
            total = pages.sumOf { it.total },
        )
    }

    fun search(site: Site, keyword: String, page: Int = 1): PagedMedia {
        if (site.type == 4) return normalizeImages(site, VodParser.parseList(Http.get(buildUrl(site.api, hashMapOf("ac" to "detail", "wd" to keyword, "quick" to "false", "pg" to page.toString()))), true))
        val params = baseParams(site)
        params["ac"] = if (site.isJsonApi) "detail" else "videolist"
        params["wd"] = keyword
        params["pg"] = page.toString()
        val body = Http.get(buildUrl(site.api, params))
        return normalizeImages(site, VodParser.parseList(body, site.isJsonApi))
    }

    fun detail(site: Site, vodId: String): MediaDetail {
        if (site.type == 4) return VodParser.parseDetail(Http.get(buildUrl(site.api, hashMapOf("ac" to "detail", "ids" to vodId, "extend" to site.ext))), true, vodId).let { it.copy(item = normalizeImage(site, it.item)) }
        var lastError: Throwable? = null
        for (action in listOf("videolist", "detail")) {
            try {
                val params = baseParams(site)
                params["ac"] = action
                params["ids"] = vodId
                val body = Http.get(buildUrl(site.api, params))
                val detail = VodParser.parseDetail(body, site.isJsonApi, vodId)
                if (detail.item.name.isNotEmpty()) {
                    return detail.copy(item = normalizeImage(site, detail.item))
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw SourceException("详情加载失败：${lastError?.message ?: "接口无返回"}")
    }

    private fun normalizeImage(site: Site, item: MediaItem): MediaItem {
        val poster = item.poster.trim()
        if (poster.isEmpty()) return item
        val resolved = site.api.toHttpUrlOrNull()?.resolve(poster)?.toString() ?: poster
        return item.copy(poster = resolved)
    }

    private fun normalizeImages(site: Site, page: PagedMedia): PagedMedia =
        page.copy(items = page.items.map { normalizeImage(site, it) })

    data class Playback(val url: String, val headers: Map<String, String>)

    fun resolvePlayback(site: Site, flag: String, id: String): Playback {
        val body = Http.get(buildUrl(site.api, hashMapOf("play" to id, "flag" to flag, "extend" to site.ext)))
        val result = try { JSONObject(body) } catch (_: Exception) { throw SourceException("播放接口返回的不是有效 JSON") }
        val url = result.optString("url").trim()
        if (result.optInt("parse", 0) != 0 || result.optString("playUrl").isNotBlank()) throw SourceException("此线路需要额外的网页解析，当前版本暂不支持")
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw SourceException("播放接口没有返回可用的视频地址")
        val rawHeaders = result.opt("header")
        val headers = when (rawHeaders) {
            is JSONObject -> rawHeaders
            is String -> runCatching { JSONObject(rawHeaders) }.getOrNull()
            else -> null
        }
        val values = mutableMapOf<String, String>()
        headers?.keys()?.forEach { key -> values[key] = headers.optString(key) }
        return Playback(url, values)
    }

    private fun baseParams(site: Site): HashMap<String, String> {
        val params = HashMap<String, String>()
        if (site.isJsonApi) params["at"] = "json"
        return params
    }

    /**
     * 在保留 api 原有 query 的前提下追加参数。
     * 有些订阅的 api 自带 token 或固定参数，整体覆盖会导致 401。
     */
    private fun buildUrl(base: String, params: Map<String, String>): String {
        val parsed = base.toHttpUrlOrNull() ?: throw SourceException("接口地址不合法：$base")
        val builder = parsed.newBuilder()
        params.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        return builder.build().toString()
    }
}
