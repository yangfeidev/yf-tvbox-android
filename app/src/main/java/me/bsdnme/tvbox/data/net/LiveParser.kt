package me.bsdnme.tvbox.data.net

import me.bsdnme.tvbox.data.model.Channel
import me.bsdnme.tvbox.data.model.ChannelGroup

/**
 * 直播列表解析器。
 *
 * 支持两种格式，靠首行自动识别：
 *
 * **M3U**（主流，带台标与 EPG）：
 * ```
 * #EXTM3U x-tvg-url="http://epg.example/e.xml"
 * #EXTINF:-1 tvg-id="CCTV1" tvg-logo="http://.../cctv1.png" group-title="央视频道",CCTV-1 综合
 * http://host/cctv1.m3u8
 * ```
 *
 * **TXT**（TVBox 传统格式，Guovin 等源在用）：
 * ```
 * 央视频道,#genre#
 * CCTV-1,http://host/cctv1.m3u8
 * 卫视频道,#genre#
 * 湖南卫视,http://host/hunan.m3u8
 * ```
 *
 * 过滤规则：
 * - 播放地址必须是 http(s)，rtp/udp/rtsp 组播在公网设备上无法播放，直接丢弃
 * - 「更新时间」「关于本源」这类伪频道丢弃，它们不是真频道
 */
object LiveParser {

    private val ATTR_REGEX = Regex("""([\w-]+)="([^"]*)"""")
    private val FAKE_CHANNEL_KEYWORDS = listOf("更新时间", "关于本源", "更新时间：", "免责")

    fun parse(raw: String): List<ChannelGroup> {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return emptyList()
        val channels = if (text.startsWith("#EXTM3U", ignoreCase = true)) {
            parseM3u(text)
        } else {
            parseTxt(text)
        }
        return groupChannels(channels)
    }

    // ------------------------------------------------------------------ M3U

    private data class ExtInfo(
        val name: String,
        val group: String,
        val logo: String,
        val tvgId: String,
        val catchup: String?,
        val headers: Map<String, String>,
    )

    private fun parseM3u(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pending: ExtInfo? = null
        val headers = mutableMapOf<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                    headers.clear()
                    headers.putAll(pending.headers)
                }
                line.startsWith("#EXTVLCOPT:http-", ignoreCase = true) -> {
                    val option = line.substringAfter(':').substringAfter("http-")
                    val key = option.substringBefore('=').lowercase()
                    val value = option.substringAfter('=', "").trim()
                    val header = when (key) { "user-agent" -> "User-Agent"; "referrer", "referer" -> "Referer"; "origin" -> "Origin"; else -> null }
                    if (header != null && value.isNotEmpty()) headers[header] = value
                }
                line.startsWith("#") -> continue
                line.contains("://") -> {
                    val info = pending
                    val playable = isPlayableUrl(line)
                    if (info != null && playable) {
                        if (!isFakeChannel(info.name)) {
                            channels += Channel(
                                name = info.name,
                                url = line,
                                logo = info.logo,
                                group = info.group,
                                tvgId = info.tvgId,
                                catchup = info.catchup,
                                headers = headers.toMap(),
                            )
                        }
                    } else if (info == null && playable) {
                        channels += Channel(name = line, url = line)
                    }
                    pending = null
                }
            }
        }
        return channels
    }

    private fun parseExtInf(line: String): ExtInfo {
        val attrs = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(line).forEach { match ->
            attrs[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        val name = line.substringAfterLast(',').trim().ifEmpty { "未命名频道" }
        val group = attrs["group-title"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "未分组"
        return ExtInfo(
            name = name,
            group = group,
            logo = attrs["tvg-logo"]?.trim().orEmpty(),
            tvgId = attrs["tvg-id"]?.trim().orEmpty(),
            catchup = attrs["catchup-source"]?.trim()?.takeIf { it.isNotEmpty() },
            headers = listOf("http-user-agent" to "User-Agent", "http-referrer" to "Referer", "http-referer" to "Referer", "http-origin" to "Origin")
                .mapNotNull { (key, header) -> attrs[key]?.takeIf { it.isNotBlank() }?.let { header to it } }.toMap(),
        )
    }

    // ------------------------------------------------------------------ TXT

    private fun parseTxt(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var currentGroup = "未分组"
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.endsWith("#genre#")) {
                currentGroup = line.removeSuffix("#genre#").trim().trimEnd(',').trim()
                    .ifEmpty { "未分组" }
                continue
            }
            val index = line.indexOf(',')
            if (index <= 0) continue
            val name = line.substring(0, index).trim()
            val url = line.substring(index + 1).trim()
            if (name.isEmpty() || !isPlayableUrl(url)) continue
            if (isFakeChannel(name)) continue
            channels += Channel(name = name, url = url, group = currentGroup)
        }
        return channels
    }

    // ------------------------------------------------------------------ 公共

    /**
     * 只保留 http(s)，并过滤 IPv6 直连地址。
     *
     * 为什么过滤 IPv6：国内 IPTV 源里大量 `http://[2409:8087:...]` 是中国移动的 IPv6 组播转单播，
     * 只有接入移动网络且分配了 IPv6 地址才连得上。实测这类地址在普通 WiFi / 其他运营商下
     * 一律 NETWORK_CONNECTION_FAILED，混在列表里只会让用户点一个黑一个。
     */
    private fun isPlayableUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("http://[") || lower.startsWith("https://[")) return false
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun isFakeChannel(name: String): Boolean =
        FAKE_CHANNEL_KEYWORDS.any { name.contains(it) }

    private fun groupChannels(channels: List<Channel>): List<ChannelGroup> {
        val order = LinkedHashMap<String, MutableList<Channel>>()
        channels.forEach { channel ->
            order.getOrPut(channel.group) { mutableListOf() }.add(channel)
        }
        return order.map { (name, list) -> ChannelGroup(name, list) }
    }
}
