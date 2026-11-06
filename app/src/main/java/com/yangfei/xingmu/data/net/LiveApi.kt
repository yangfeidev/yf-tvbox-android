package com.yangfei.xingmu.data.net

import com.yangfei.xingmu.data.model.ChannelGroup

/**
 * 直播源加载。
 *
 * 直播列表体积差异极大：小的 4KB（几十个台），大的 500KB+（上千个台）。
 * 超时给到 45 秒：聚合地址可能要为每个上游源单独探测，7 秒 × 多批是常态，
 * 用点播那套 15 秒超时会必然失败。
 * 结果进 [LiveCache]，10 分钟内切 Tab 不重复下载。
 */
object LiveApi {

    private const val TIMEOUT_SECONDS = 45L

    fun load(url: String, forceRefresh: Boolean = false): List<ChannelGroup> {
        if (!forceRefresh) {
            LiveCache.get(url)?.let { return it }
        }
        val groups = LiveParser.parse(Http.get(url, timeoutSeconds = TIMEOUT_SECONDS))
        if (groups.isNotEmpty()) {
            LiveCache.put(url, groups)
        }
        return groups
    }
}
