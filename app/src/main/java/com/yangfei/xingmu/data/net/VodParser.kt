package com.yangfei.xingmu.data.net

import android.util.Xml
import com.yangfei.xingmu.data.model.Category
import com.yangfei.xingmu.data.model.Episode
import com.yangfei.xingmu.data.model.MediaDetail
import com.yangfei.xingmu.data.model.MediaItem
import com.yangfei.xingmu.data.model.PagedMedia
import com.yangfei.xingmu.data.model.PlaySource
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * CMS 响应解析器。
 *
 * 同时支持两类源站返回（对应 [com.yangfei.xingmu.data.model.Site.type] 的 0 与 1）：
 * - **JSON**（type 1）：苹果CMS v10 `api.php/provide/vod/?ac=...&at=json`
 * - **XML**（type 0）：`api.php/provide/vod/?ac=...`（默认 XML）
 *
 * 播放地址的三层分隔符（TVBox 约定）：
 * ```
 * 线路一$$$线路二                       ← 线路之间用 $$$
 * 第1集$url#第2集$url                  ← 集数之间用 #
 * 第1集$http://.../1.m3u8              ← 名称与地址之间用 $
 * ```
 *
 * 容错原则：字段缺失一律降级为空值，绝不抛异常打断整页渲染；
 * 只有根节点完全无法识别时才抛 [SourceException]，由上层提示用户。
 */
object VodParser {

    private const val SOURCE_SEPARATOR = "\$\$\$"
    private const val EPISODE_SEPARATOR = "#"
    private const val FIELD_SEPARATOR = "\$"

    // ------------------------------------------------------------------ 列表

    fun parseList(body: String, isJson: Boolean): PagedMedia =
        if (isJson) parseJsonList(body) else parseXmlList(body)

    private fun parseJsonList(body: String): PagedMedia {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw SourceException("返回内容不是合法 JSON，可能接口地址已失效")
        }
        val items = mutableListOf<MediaItem>()
        val array = root.optJSONArray("list")
        if (array != null) {
            for (i in 0 until array.length()) {
                val node = array.optJSONObject(i) ?: continue
                toItem(node)?.let { items += it }
            }
        }
        return PagedMedia(
            items = items,
            page = root.optInt("page", 1),
            pageCount = root.optInt("pagecount", 1).coerceAtLeast(1),
            total = root.optInt("total", items.size),
            categories = parseCategories(root.optJSONArray("class")),
        )
    }

    private fun parseXmlList(body: String): PagedMedia {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(body))

        val items = mutableListOf<MediaItem>()
        val categories = mutableListOf<Category>()
        var page = 1
        var pageCount = 1
        var total = 0

        var current: MutableMap<String, String>? = null
        var currentTag = ""
        var inClass = false
        var sawList = false
        var tyId: String? = null
        var tyParentId = ""
        val tyText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "list" -> {
                        sawList = true
                        page = parser.getAttributeValue(null, "page")?.toIntOrNull() ?: 1
                        pageCount = parser.getAttributeValue(null, "pagecount")?.toIntOrNull() ?: 1
                        total = parser.getAttributeValue(null, "recordcount")?.toIntOrNull() ?: 0
                    }
                    "video" -> {
                        current = mutableMapOf()
                        currentTag = ""
                    }
                    "class" -> inClass = true
                    "ty" -> {
                        tyId = parser.getAttributeValue(null, "id")
                        tyParentId = parser.getAttributeValue(null, "pid") ?: ""
                        tyText.clear()
                        currentTag = "ty"
                    }
                    else -> currentTag = parser.name.lowercase()
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (inClass) {
                        if (currentTag == "ty") tyText.append(text)
                    } else {
                        val map = current
                        if (map != null && currentTag.isNotEmpty()) {
                            map[currentTag] = (map[currentTag] ?: "") + text
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "video" -> {
                        val map = current
                        if (map != null) {
                            val name = map["name"]?.trim().orEmpty()
                            if (name.isNotEmpty()) {
                                items += MediaItem(
                                    id = map["id"]?.trim().orEmpty(),
                                    name = name,
                                    poster = map["pic"]?.trim().orEmpty(),
                                    remarks = map["note"]?.trim().orEmpty(),
                                    typeId = map["tid"]?.trim().orEmpty(),
                                    year = map["year"]?.trim().orEmpty(),
                                    area = map["area"]?.trim().orEmpty(),
                                    categoryName = map["type"]?.trim().orEmpty(),
                                )
                            }
                        }
                        current = null
                        currentTag = ""
                    }
                    "class" -> inClass = false
                    "ty" -> {
                        val id = tyId
                        val label = tyText.toString().trim()
                        if (id != null && label.isNotEmpty()) {
                            categories += Category(id, label, tyParentId)
                        }
                        tyId = null
                        currentTag = ""
                    }
                    else -> if (currentTag == parser.name.lowercase()) currentTag = ""
                }
            }
            event = parser.next()
        }

        if (items.isEmpty() && categories.isEmpty() && !sawList) {
            throw SourceException("XML 中没有可识别的内容，可能接口返回了错误页")
        }
        return PagedMedia(items, page, pageCount.coerceAtLeast(1), total, categories)
    }

    // ------------------------------------------------------------------ 详情

    fun parseDetail(body: String, isJson: Boolean, fallbackId: String): MediaDetail =
        if (isJson) parseJsonDetail(body, fallbackId) else parseXmlDetail(body, fallbackId)

    private fun parseJsonDetail(body: String, fallbackId: String): MediaDetail {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw SourceException("详情返回的不是合法 JSON")
        }
        val array = root.optJSONArray("list")
        if (array == null || array.length() == 0) {
            throw SourceException("未取到详情数据")
        }
        val node = array.optJSONObject(0) ?: throw SourceException("未取到详情数据")
        return MediaDetail(
            item = toItem(node) ?: MediaItem(id = fallbackId, name = ""),
            content = cleanHtml(node.str("vod_content")),
            actor = node.str("vod_actor"),
            director = node.str("vod_director"),
            playSources = buildPlaySources(
                from = node.str("vod_play_from"),
                url = node.str("vod_play_url"),
            ),
        )
    }

    private fun parseXmlDetail(body: String, fallbackId: String): MediaDetail {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(body))

        val fields = mutableMapOf<String, String>()
        val sources = mutableListOf<PlaySource>()
        var currentTag = ""
        var ddFlag = ""
        val ddText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "dd" -> {
                        ddFlag = parser.getAttributeValue(null, "flag")
                            ?: "线路 ${sources.size + 1}"
                        ddText.clear()
                        currentTag = "dd"
                    }
                    else -> currentTag = parser.name.lowercase()
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (currentTag == "dd") {
                        ddText.append(text)
                    } else if (currentTag.isNotEmpty()) {
                        fields[currentTag] = (fields[currentTag] ?: "") + text
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "dd" -> {
                        val episodes = splitEpisodes(ddText.toString())
                        if (episodes.isNotEmpty()) {
                            sources += PlaySource(ddFlag.ifBlank { "线路 ${sources.size + 1}" }, episodes)
                        }
                        currentTag = ""
                    }
                    else -> if (currentTag == parser.name.lowercase()) currentTag = ""
                }
            }
            event = parser.next()
        }

        val name = fields["name"]?.trim().orEmpty()
        if (name.isEmpty()) {
            throw SourceException("详情中没有找到影片信息")
        }
        return MediaDetail(
            item = MediaItem(
                id = fields["id"]?.trim().takeUnless { it.isNullOrEmpty() } ?: fallbackId,
                name = name,
                poster = fields["pic"]?.trim().orEmpty(),
                remarks = fields["note"]?.trim().orEmpty(),
                typeId = fields["tid"]?.trim().orEmpty(),
                year = fields["year"]?.trim().orEmpty(),
                area = fields["area"]?.trim().orEmpty(),
                categoryName = fields["type"]?.trim().orEmpty(),
            ),
            content = cleanHtml(fields["des"]?.trim().orEmpty()),
            actor = fields["actor"]?.trim().orEmpty(),
            director = fields["director"]?.trim().orEmpty(),
            playSources = sources,
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun buildPlaySources(from: String, url: String): List<PlaySource> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return emptyList()
        val names = if (from.isBlank()) emptyList() else from.split(SOURCE_SEPARATOR)
        val groups = trimmed.split(SOURCE_SEPARATOR)
        val sources = mutableListOf<PlaySource>()
        groups.forEachIndexed { index, group ->
            val episodes = splitEpisodes(group)
            if (episodes.isEmpty()) return@forEachIndexed
            val name = names.getOrNull(index)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "线路 ${sources.size + 1}"
            sources += PlaySource(name, episodes)
        }
        return sources
    }

    private fun splitEpisodes(raw: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (segment in raw.split(EPISODE_SEPARATOR)) {
            val entry = segment.trim()
            if (entry.isEmpty()) continue
            val index = entry.indexOf(FIELD_SEPARATOR)
            if (index <= 0) {
                // 没有「名称$地址」结构时按裸地址处理
                if (entry.startsWith("http")) {
                    episodes += Episode("第 ${episodes.size + 1} 集", entry)
                }
                continue
            }
            val name = entry.substring(0, index).trim()
            val url = entry.substring(index + 1).trim()
            if (url.isEmpty()) continue
            episodes += Episode(
                name.ifEmpty { "第 ${episodes.size + 1} 集" },
                url,
            )
        }
        return episodes
    }

    private fun toItem(node: JSONObject): MediaItem? {
        val name = node.str("vod_name")
        if (name.isEmpty()) return null
        return MediaItem(
            id = node.str("vod_id"),
            name = name,
            poster = node.str("vod_pic"),
            remarks = node.str("vod_remarks"),
            typeId = node.str("type_id"),
            year = node.str("vod_year"),
            area = node.str("vod_area"),
            categoryName = node.str("type_name"),
        )
    }

    private fun parseCategories(array: org.json.JSONArray?): List<Category> {
        if (array == null) return emptyList()
        val result = mutableListOf<Category>()
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i) ?: continue
            val id = node.str("type_id")
            val name = node.str("type_name")
            if (id.isNotEmpty() && name.isNotEmpty()) {
                result += Category(id, name, node.str("type_pid"))
            }
        }
        return result
    }

    private fun JSONObject.str(key: String): String {
        val value = opt(key) ?: return ""
        if (value == JSONObject.NULL) return ""
        return value.toString().trim().let { if (it == "null") "" else it }
    }

    private fun cleanHtml(input: String): String {
        if (input.isBlank()) return ""
        return input
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
