package com.yangfei.xingmu.data.model

/**
 * 数据模型。
 *
 * 结构：
 * - [Subscription]：用户添加的订阅记录
 * - [Site]：订阅里的单个源站（对应 TVBox JSON 的 sites 数组）
 * - [WarehouseEntry]：多仓订阅里的子仓库
 * - [LiveSourceEntry]：订阅里的 lives 数组，指向外部 m3u/txt 直播列表
 * - [MediaItem] / [MediaDetail] / [PlaySource] / [Episode]：点播内容
 * - [Channel] / [ChannelGroup]：直播频道与分组
 *
 * 源站类型说明（决定能不能用）：
 * - 0 = 苹果CMS XML 接口，可直接请求
 * - 1 = 苹果CMS JSON 接口，可直接请求
 * - 3 = Spider，api 字段是 jar 里的类名，需要 JVM 执行，本项目暂不支持
 * - 4 = 远程 Spider HTTP 接口
 */

data class Subscription(
    val id: String,
    val url: String,
    val name: String,
    val addedAt: Long,
    val updatedAt: Long? = null,
    val siteCount: Int = 0,
)

data class Site(
    val key: String,
    val name: String,
    val api: String,
    val type: Int,
    val searchable: Boolean,
    val quickSearch: Boolean,
    val ext: String = "",
) {
    /** type 1 走 JSON，type 0 走 XML */
    val isJsonApi: Boolean get() = type == 1 || type == 4

    /** 只有 0 / 1 且 api 是网址时才可用 */
    val usable: Boolean
        get() = api.startsWith("http") && (type == 0 || type == 1 || type == 4)

    /** type 3 / 4 是 jar 爬虫，需要 JVM，当前无法执行 */
    val isSpider: Boolean get() = type == 3

    /** 不可用原因，用于设置页向用户解释 */
    val unavailableReason: String?
        get() = when {
            usable -> null
            isSpider -> "需要 spider 运行时，当前版本不支持"
            api.isBlank() -> "缺少接口地址"
            else -> "未知源类型 type=$type"
        }
}

data class WarehouseEntry(val name: String, val url: String)

data class LiveSourceEntry(val name: String, val url: String, val group: String = "")

class SubscriptionDocument(
    val sites: List<Site> = emptyList(),
    val warehouse: List<WarehouseEntry> = emptyList(),
    val liveSources: List<LiveSourceEntry> = emptyList(),
    val parses: List<String> = emptyList(),
) {
    val usableSites: List<Site> get() = sites.filter { it.usable }
    val isWarehouse: Boolean get() = warehouse.isNotEmpty()
}

data class Category(val typeId: String, val name: String, val parentId: String = "")

data class MediaItem(
    val id: String,
    val name: String,
    val poster: String = "",
    val remarks: String = "",
    val typeId: String = "",
    val year: String = "",
    val area: String = "",
    val categoryName: String = "",
) {
    val hasPoster: Boolean get() = poster.isNotEmpty()
}

data class Episode(val name: String, val url: String) {
    /**
     * 是否直链。CMS 返回的播放地址可能是网页地址，需要 parses 接口解析，
     * 当前版本只播直链，非直链在播放器里给出明确提示而不是黑屏。
     */
    val isPlayable: Boolean
        get() {
            val lower = url.lowercase()
            return lower.startsWith("http") &&
                (lower.contains(".m3u8") || lower.contains(".mp4") ||
                    lower.contains(".flv") || lower.contains(".mkv") ||
                    lower.contains(".ts"))
        }
}

data class PlaySource(val name: String, val episodes: List<Episode>)

data class MediaDetail(
    val item: MediaItem,
    val content: String = "",
    val actor: String = "",
    val director: String = "",
    val playSources: List<PlaySource> = emptyList(),
) {
    val usableSources: List<PlaySource> get() = playSources.filter { it.episodes.isNotEmpty() }

    val metaLine: String
        get() = listOfNotNull(
            item.year.takeIf { it.isNotBlank() },
            item.area.takeIf { it.isNotBlank() },
            item.categoryName.takeIf { it.isNotBlank() },
            item.remarks.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

data class PagedMedia(
    val items: List<MediaItem> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
    val categories: List<Category> = emptyList(),
) {
    val hasMore: Boolean get() = page < pageCount
}

data class Channel(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "未分组",
    val tvgId: String = "",
    val catchup: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class ChannelGroup(val name: String, val channels: List<Channel>)

/** 收藏 / 历史条目 */
data class LibraryEntry(
    val siteKey: String,
    val mediaId: String,
    val name: String,
    val poster: String = "",
    val remarks: String = "",
    val episodeName: String = "",
    val sourceName: String = "",
    val progress: Float = 0f,
    val updatedAt: Long = 0L,
) {
    val uniqueKey: String get() = "$siteKey::$mediaId"
}

data class ResumeTarget(val sourceIndex: Int, val episodeIndex: Int, val progress: Float)

fun findResumeTarget(sources: List<PlaySource>, history: LibraryEntry?): ResumeTarget? {
    if (history == null || history.episodeName.isBlank() || history.progress <= 0f || history.progress >= 0.98f) return null
    if (history.sourceName.isNotBlank()) {
        val sourceIndex = sources.indexOfFirst { it.name == history.sourceName }
        if (sourceIndex >= 0) {
            val episodeIndex = sources[sourceIndex].episodes.indexOfFirst { it.name == history.episodeName }
            if (episodeIndex >= 0) return ResumeTarget(sourceIndex, episodeIndex, history.progress)
        }
    }
    sources.forEachIndexed { sourceIndex, source ->
        val episodeIndex = source.episodes.indexOfFirst { it.name == history.episodeName }
        if (episodeIndex >= 0) return ResumeTarget(sourceIndex, episodeIndex, history.progress)
    }
    return null
}

data class AppSettings(
    val forceTelevision: Boolean = false,
    val autoUpdateOnLaunch: Boolean = true,
    val activeSubscriptionId: String = "",
    val currentSiteKey: String = "",
    /** 自定义直播源（m3u / txt）。为空时使用订阅里自带的 lives */
    val liveUrl: String = "",
    /** 首次启动自动导入内置单仓订阅 */
    val autoAddDefault: Boolean = true,
)

/** 收藏 / 历史条目转成海报卡数据，点击时仍按原 id 打开详情 */
fun LibraryEntry.toMediaItem(): MediaItem = MediaItem(
    id = mediaId,
    name = name,
    poster = poster,
    remarks = episodeName.ifBlank { remarks },
)
