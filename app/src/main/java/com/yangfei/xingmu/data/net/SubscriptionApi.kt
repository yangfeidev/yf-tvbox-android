package com.yangfei.xingmu.data.net

import com.yangfei.xingmu.data.model.LiveSourceEntry
import com.yangfei.xingmu.data.model.Site
import com.yangfei.xingmu.data.model.SubscriptionDocument
import com.yangfei.xingmu.data.model.WarehouseEntry
import org.json.JSONObject

/**
 * 订阅拉取与解析。
 *
 * 支持三种形态：
 * 1. **标准订阅**：`{"sites":[...],"lives":[...],"parses":[...]}`
 * 2. **多仓订阅**：`{"urls":[{"name":"仓库A","url":"https://..."}]}`，需用户选择子仓库
 * 3. **裸地址**：直接返回一个 CMS 接口地址字符串，生成单一源站
 *
 * 容错：sites 里 type 3/4 的 spider 源会被保留但要标记为不可用，
 * 不静默丢弃——设置页要能告诉用户「有多少个源因为缺 JVM 而用不了」。
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

    fun fetch(url: String): SubscriptionDocument = parse(Http.get(normalizeUrl(url)))

    fun parse(raw: String): SubscriptionDocument {
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
        val sites = mutableListOf<Site>()
        for (i in 0 until sitesArray.length()) {
            val node = sitesArray.optJSONObject(i) ?: continue
            val name = node.optString("name").trim()
            if (name.isEmpty()) continue
            val api = node.optString("api").trim().ifEmpty { node.optString("api_").trim() }
            sites += Site(
                key = node.optString("key").trim().ifEmpty { name },
                name = name,
                api = api,
                type = node.optInt("type", 1),
                searchable = node.optInt("searchable", 1) != 0,
                quickSearch = node.optInt("quickSearch", 0) != 0,
                ext = node.optString("ext"),
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
