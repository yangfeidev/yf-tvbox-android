package com.yangfei.xingmu.data.net

import com.yangfei.xingmu.data.model.ChannelGroup

/**
 * 直播列表的内存缓存。
 *
 * 为什么要缓存：直播聚合地址（例如自建 Worker 的 `/live`）每次请求都要现场探测上游源，
 * 一个 500KB 的列表可能要十几到几十秒才回。用户切一次 Tab 就重新下载一遍不可接受。
 *
 * 策略：同一个地址 10 分钟内复用内存结果；换地址或过期则重新拉取。
 * 只放内存，进程重启后自然失效，不做磁盘缓存——直播源地址变化频繁，落盘容易给出过期频道。
 */
internal object LiveCache {

    private const val TTL_MS = 10 * 60 * 1000L

    private var cachedUrl: String = ""
    private var cachedAt: Long = 0
    private var cachedGroups: List<ChannelGroup> = emptyList()

    fun get(url: String): List<ChannelGroup>? {
        if (url != cachedUrl) return null
        if (System.currentTimeMillis() - cachedAt > TTL_MS) return null
        return cachedGroups.takeIf { it.isNotEmpty() }
    }

    fun put(url: String, groups: List<ChannelGroup>) {
        cachedUrl = url
        cachedAt = System.currentTimeMillis()
        cachedGroups = groups
    }

    fun invalidate() {
        cachedUrl = ""
        cachedAt = 0
        cachedGroups = emptyList()
    }
}
