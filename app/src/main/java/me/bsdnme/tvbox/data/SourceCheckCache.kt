package me.bsdnme.tvbox.data

import me.bsdnme.tvbox.data.model.Site
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Persisted evidence applies only to the exact source configuration and network. */
data class SourceCheckEntry(val available: Boolean, val failures: Int = 0)
data class SourceCheckSnapshot(val network: String, val checkedAt: Long, val entries: Map<String, SourceCheckEntry>) {
    fun isFresh(now: Long, ttl: Long = 30 * 60 * 1000L): Boolean = checkedAt > 0 && now >= checkedAt && now - checkedAt < ttl
}

object SourceCheckCache {
    fun identity(site: Site): String {
        val bytes = JSONArray().put(site.key).put(site.type).put(site.api).put(site.ext).put(site.jar).put(site.configUrl).toString().toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun merge(previous: SourceCheckSnapshot?, network: String, now: Long, results: Map<String, Boolean>): SourceCheckSnapshot {
        val entries = results.mapValues { (id, success) ->
            val old = previous?.takeIf { it.network == network }?.entries?.get(id)
            if (success) SourceCheckEntry(true)
            else {
                val failures = ((old?.failures ?: 0) + 1).coerceAtMost(2)
                SourceCheckEntry(old?.available == true && failures < 2, failures)
            }
        }
        return SourceCheckSnapshot(network, now, entries)
    }

    fun encode(snapshots: List<SourceCheckSnapshot>): String = JSONArray().apply {
        snapshots.forEach { snapshot -> put(JSONObject().apply {
            put("network", snapshot.network); put("checkedAt", snapshot.checkedAt)
            put("entries", JSONObject().apply { snapshot.entries.forEach { (id, entry) ->
                put(id, JSONObject().put("available", entry.available).put("failures", entry.failures))
            } })
        }) }
    }.toString()

    fun decode(raw: String): List<SourceCheckSnapshot> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val network = item.optString("network").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val nodes = item.optJSONObject("entries") ?: return@mapNotNull null
            val entries = nodes.keys().asSequence().mapNotNull { key ->
                nodes.optJSONObject(key)?.let { key to SourceCheckEntry(it.optBoolean("available"), it.optInt("failures").coerceIn(0, 2)) }
            }.toMap()
            SourceCheckSnapshot(network, item.optLong("checkedAt"), entries)
        }
    }.getOrDefault(emptyList())
}
