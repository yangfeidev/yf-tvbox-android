package me.bsdnme.tvbox.data.net

import me.bsdnme.tvbox.core.DefaultSources
import me.bsdnme.tvbox.data.model.LiveSourceEntry
import me.bsdnme.tvbox.data.model.Site
import me.bsdnme.tvbox.data.model.SubscriptionDocument
import me.bsdnme.tvbox.data.model.WarehouseEntry
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 订阅拉取与解析。
 *
 * 支持三种形态：
 * 1. **标准订阅**：`{"sites":[...],"lives":[...],"parses":[...]}`
 * 2. **多仓订阅**：`{"urls":[{"name":"仓库A","url":"https://..."}]}`，需用户选择子仓库
 * 3. **裸地址**：直接返回一个 CMS 接口地址字符串，生成单一源站
 *
 * 保留 type 3 运行时及来源信息；不支持的脚本和其他类型不会自动执行。
 */
object SubscriptionApi {

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    const val TIMEOUT_SECONDS = 45L

    fun fetchRaw(url: String): String {
        val normalized = normalizeUrl(url)
        val requestUrl = if (normalized == DefaultSources.BOX) "$normalized?include_candidates=1" else normalized
        return Http.get(requestUrl, timeoutSeconds = TIMEOUT_SECONDS, readTimeoutSeconds = TIMEOUT_SECONDS)
    }

    fun fetch(url: String): SubscriptionDocument = parse(fetchRaw(url), normalizeUrl(url))

    /**
     * 统一源使用服务端生成的 key。保留旧订阅中同一接口的 key，
     * 让选择、收藏和历史记录在切换订阅地址后仍能关联到对应源。
     */
    fun preserveSourceKeys(raw: String, previous: SubscriptionDocument?, keys: MutableMap<String, String>): String {
        fun identity(site: Site): String = JSONArray().put(site.type).put(site.api).put(site.ext).apply {
            if (site.type == 3) put(site.jar).put(site.configUrl)
        }.toString()

        val oldSites = previous?.sites.orEmpty()
        val oldKeyCounts = oldSites.groupingBy { it.key }.eachCount()
        oldSites.forEach { site ->
            if (site.key.isNotBlank() && oldKeyCounts[site.key] == 1) keys.putIfAbsent(identity(site), site.key)
        }
        val root = JSONObject(raw)
        val array = root.optJSONArray("sites") ?: return raw
        val parsed = parse(raw).sites
        val occupied = parsed.map { it.key }.toMutableSet()
        for (index in 0 until array.length()) {
            val node = array.optJSONObject(index) ?: continue
            val original = node.optString("key").trim().ifEmpty { node.optString("name").trim() }
            val site = parsed.firstOrNull { it.key == original } ?: continue
            val id = identity(site)
            val oldKey = keys[id]
            if (oldKey != null && (oldKey == original || oldKey !in occupied)) {
                occupied.remove(original)
                occupied.add(oldKey)
                node.put("key", oldKey)
            } else {
                keys[id] = original
            }
        }
        return root.toString()
    }

    fun parse(raw: String, baseUrl: String = ""): SubscriptionDocument {
        val text = raw.trim()
        if (text.isEmpty()) throw SourceException("订阅内容为空")

        if (text.startsWith("http://") || text.startsWith("https://")) {
            val api = text.lineSequence().first().trim()
            return SubscriptionDocument(
                sites = listOf(
                    Site(
                        key = "direct",
                        name = api.substringAfter("://").substringBefore("/"),
                        api = api,
                        type = 1,
                        searchable = true,
                        quickSearch = true,
                    ),
                ),
            )
        }

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SourceException("订阅内容不是合法 JSON，可能地址已失效或返回了网页")
        }

        val urls = root.optJSONArray("urls")
        if (urls != null && urls.length() > 0) {
            val entries = mutableListOf<WarehouseEntry>()
            for (i in 0 until urls.length()) {
                val node = urls.optJSONObject(i) ?: continue
                val url = node.optString("url").trim()
                if (url.isEmpty()) continue
                val name = node.optString("name").trim().ifEmpty { url }
                entries += WarehouseEntry(name, url)
            }
            if (entries.isEmpty()) throw SourceException("多仓订阅里没有可用的子仓库地址")
            return SubscriptionDocument(warehouse = entries)
        }

        val sitesArray = root.optJSONArray("sites")
            ?: throw SourceException("订阅里没有 sites 节点，可能不是 TVBox 格式")
        fun resolveJar(value: String, base: String): String {
            val parts = value.trim().split(';', limit = 2)
            val first = parts.firstOrNull().orEmpty()
            if (first.isBlank()) return ""
            val resolved = first.toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(first) ?: return ""
            return resolved.toString() + if (parts.size > 1) ";${parts[1]}" else ""
        }
        val sites = mutableListOf<Site>()
        for (i in 0 until sitesArray.length()) {
            val node = sitesArray.optJSONObject(i) ?: continue
            val name = node.optString("name").trim()
            if (name.isEmpty()) continue
            val api = node.optString("api").trim().ifEmpty { node.optString("api_").trim() }
            val type = node.optInt("type", 1)
            val configUrl = node.optString("configUrl").trim().ifEmpty { baseUrl }
            val jarValue = if (node.has("jar")) node.opt("jar") else root.opt("spider")
            val jar = jarValue as? String ?: ""
            sites += Site(
                key = node.optString("key").trim().ifEmpty { name },
                name = name,
                api = api,
                type = type,
                searchable = node.optInt("searchable", 1) != 0,
                quickSearch = node.optInt("quickSearch", 0) != 0,
                ext = node.optString("ext"),
                jar = if (type == 3) resolveJar(jar, configUrl) else "",
                configUrl = if (type == 3) configUrl else "",
            )
        }
        if (sites.isEmpty()) throw SourceException("订阅中的 sites 全部缺少名称")

        val liveSources = mutableListOf<LiveSourceEntry>()
        val lives = root.optJSONArray("lives")
        if (lives != null) {
            for (i in 0 until lives.length()) {
                val node = lives.optJSONObject(i) ?: continue
                val url = node.optString("url").trim()
                if (url.isEmpty()) continue
                liveSources += LiveSourceEntry(
                    name = node.optString("name").trim().ifEmpty { "直播源 ${i + 1}" },
                    url = url,
                    group = node.optString("group").trim(),
                )
            }
        }

        val parses = mutableListOf<String>()
        val parsesArray = root.optJSONArray("parses")
        if (parsesArray != null) {
            for (i in 0 until parsesArray.length()) {
                val node = parsesArray.optJSONObject(i) ?: continue
                val url = node.optString("url").trim()
                if (url.isNotEmpty()) parses += url
            }
        }

        return SubscriptionDocument(
            sites = sites,
            liveSources = liveSources,
            parses = parses,
        )
    }
}
