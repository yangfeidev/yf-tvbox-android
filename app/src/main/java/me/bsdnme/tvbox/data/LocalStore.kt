package me.bsdnme.tvbox.data

import android.content.Context
import me.bsdnme.tvbox.data.model.AppSettings
import me.bsdnme.tvbox.data.model.LibraryEntry
import me.bsdnme.tvbox.data.model.Subscription
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地持久化。
 *
 * 结构：
 * - 订阅列表、收藏、历史、设置 → SharedPreferences（体积小、读频繁）
 * - 订阅原文缓存 → 文件系统（单个订阅 JSON 可达数百 KB，不适合放 prefs）
 *
 * 缓存目录：`filesDir/feigemedia/subscriptions/<id>.json`
 * 目的：源站临时不可达时，仍能用上次拉到的配置进入 App，不至于白屏。
 */
class LocalStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheDir: File = File(context.filesDir, "feigemedia/subscriptions")

    // ------------------------------------------------------------------ 订阅

    fun loadSubscriptions(): List<Subscription> {
        val raw = prefs.getString(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { toSubscription(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun saveSubscriptions(list: List<Subscription>) {
        val array = JSONArray()
        list.forEach { array.put(fromSubscription(it)) }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, array.toString()).apply()
    }

    var activeSubscriptionId: String
        get() = prefs.getString(KEY_ACTIVE_SUBSCRIPTION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACTIVE_SUBSCRIPTION, value).apply()

    var currentSiteKey: String
        get() = prefs.getString(KEY_CURRENT_SITE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CURRENT_SITE, value).apply()

    // ------------------------------------------------------------------ 订阅原文缓存

    fun readSubscriptionCache(id: String): String? = runCatching {
        val file = File(cacheDir, "$id.json")
        if (file.exists()) file.readText() else null
    }.getOrNull()

    fun writeSubscriptionCache(id: String, raw: String) {
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            File(cacheDir, "$id.json").writeText(raw)
        }
    }

    fun readSourceKeys(id: String): MutableMap<String, String> = runCatching {
        val raw = File(cacheDir, "$id.source-keys.json").readText()
        val obj = JSONObject(raw)
        obj.keys().asSequence().associateWith { obj.getString(it) }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    fun writeSourceKeys(id: String, keys: Map<String, String>) {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        File(cacheDir, "$id.source-keys.json").writeText(JSONObject(keys).toString())
    }

    @Synchronized fun readSourceChecks(id: String): List<SourceCheckSnapshot> =
        SourceCheckCache.decode(prefs.getString("source_checks_$id", "[]").orEmpty())

    @Synchronized fun writeSourceChecks(id: String, snapshot: SourceCheckSnapshot) {
        val existing = readSourceChecks(id)
        val old = existing.firstOrNull { it.network == snapshot.network }
        val combined = snapshot.copy(
            checkedAt = if (snapshot.checkedAt > 0) snapshot.checkedAt else old?.checkedAt ?: 0,
            entries = old?.entries.orEmpty() + snapshot.entries,
        )
        val snapshots = (listOf(combined) + existing.filterNot { it.network == snapshot.network }).take(8)
        prefs.edit().putString("source_checks_$id", SourceCheckCache.encode(snapshots)).apply()
    }

    // ------------------------------------------------------------------ 直播列表缓存

    /**
     * 直播聚合地址每次现场探测上游要几十秒，必须落盘缓存。
     * 用文件修改时间做 TTL，超过 [ttlMs] 视为过期，重新拉取。
     */
    fun readLiveCache(url: String, ttlMs: Long): String? = runCatching {
        val file = File(cacheDir, "live_${url.hashCode()}.m3u")
        if (!file.exists()) return@runCatching null
        if (System.currentTimeMillis() - file.lastModified() > ttlMs) return@runCatching null
        file.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun writeLiveCache(url: String, raw: String) {
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            File(cacheDir, "live_${url.hashCode()}.m3u").writeText(raw)
        }
    }

    // ------------------------------------------------------------------ 收藏与历史

    fun loadFavorites(): List<LibraryEntry> = loadEntries(KEY_FAVORITES)

    fun saveFavorites(list: List<LibraryEntry>) = saveEntries(KEY_FAVORITES, list)

    fun loadHistory(): List<LibraryEntry> = loadEntries(KEY_HISTORY)

    fun saveHistory(list: List<LibraryEntry>) = saveEntries(KEY_HISTORY, list)

    fun loadPinnedSites(): Set<String> = prefs.getStringSet("pinned_sites", emptySet())?.toSet().orEmpty()
    fun savePinnedSites(keys: Set<String>) { prefs.edit().putStringSet("pinned_sites", keys.toSet()).apply() }

    fun loadSearchHistory(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("search_history", "[]"))
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun saveSearchHistory(list: List<String>) {
        prefs.edit().putString("search_history", JSONArray(list).toString()).apply()
    }

    private fun loadEntries(key: String): List<LibraryEntry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { toEntry(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(key: String, list: List<LibraryEntry>) {
        val array = JSONArray()
        list.forEach { array.put(fromEntry(it)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    // ------------------------------------------------------------------ 设置

    fun loadSettings(): AppSettings = AppSettings(
        forceTelevision = prefs.getBoolean(KEY_FORCE_TV, false),
        autoUpdateOnLaunch = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        activeSubscriptionId = activeSubscriptionId,
        currentSiteKey = currentSiteKey,
        liveUrl = prefs.getString(KEY_LIVE_URL, "").orEmpty(),
        autoAddDefault = prefs.getBoolean(KEY_AUTO_ADD_DEFAULT, true),
    )

    fun setLiveUrl(value: String) = prefs.edit().putString(KEY_LIVE_URL, value).apply()

    fun setForceTelevision(value: Boolean) = prefs.edit().putBoolean(KEY_FORCE_TV, value).apply()

    fun setAutoUpdate(value: Boolean) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    // ------------------------------------------------------------------ 序列化

    private fun toSubscription(node: JSONObject) = Subscription(
        id = node.optString("id"),
        url = node.optString("url"),
        name = node.optString("name"),
        addedAt = node.optLong("addedAt"),
        updatedAt = node.optLong("updatedAt").takeIf { it > 0 },
        siteCount = node.optInt("siteCount"),
    )

    private fun fromSubscription(sub: Subscription) = JSONObject().apply {
        put("id", sub.id)
        put("url", sub.url)
        put("name", sub.name)
        put("addedAt", sub.addedAt)
        put("updatedAt", sub.updatedAt ?: 0L)
        put("siteCount", sub.siteCount)
    }

    private fun toEntry(node: JSONObject) = LibraryEntry(
        siteKey = node.optString("siteKey"),
        mediaId = node.optString("mediaId"),
        name = node.optString("name"),
        poster = node.optString("poster"),
        remarks = node.optString("remarks"),
        typeId = node.optString("typeId"),
        categoryName = node.optString("categoryName"),
        episodeName = node.optString("episodeName"),
        sourceName = node.optString("sourceName"),
        progress = node.optDouble("progress", 0.0).toFloat(),
        updatedAt = node.optLong("updatedAt"),
    )

    private fun fromEntry(entry: LibraryEntry) = JSONObject().apply {
        put("siteKey", entry.siteKey)
        put("mediaId", entry.mediaId)
        put("name", entry.name)
        put("poster", entry.poster)
        put("remarks", entry.remarks)
        put("typeId", entry.typeId)
        put("categoryName", entry.categoryName)
        put("episodeName", entry.episodeName)
        put("sourceName", entry.sourceName)
        put("progress", entry.progress.toDouble())
        put("updatedAt", entry.updatedAt)
    }

    companion object {
        private const val PREFS_NAME = "feigemedia"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HISTORY = "history"
        private const val KEY_ACTIVE_SUBSCRIPTION = "active_subscription"
        private const val KEY_CURRENT_SITE = "current_site"
        private const val KEY_FORCE_TV = "force_tv"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_LIVE_URL = "live_url"
        private const val KEY_AUTO_ADD_DEFAULT = "auto_add_default"
    }
}
