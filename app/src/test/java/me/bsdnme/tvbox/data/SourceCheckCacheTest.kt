package me.bsdnme.tvbox.data

import me.bsdnme.tvbox.data.model.Site
import org.junit.Assert.*
import org.junit.Test

class SourceCheckCacheTest {
    private val site = Site("a", "源", "https://example.com/api", 1, true, false)

    @Test fun `source identity changes when executable configuration changes`() {
        val identity = SourceCheckCache.identity(site)
        assertEquals(identity, SourceCheckCache.identity(site.copy(name = "新名称")))
        assertNotEquals(identity, SourceCheckCache.identity(site.copy(api = "https://other.example/api")))
        assertNotEquals(identity, SourceCheckCache.identity(site.copy(ext = "different")))
        assertNotEquals(identity, SourceCheckCache.identity(site.copy(jar = "https://example.com/a.jar")))
    }

    @Test fun `one failure preserves a previously healthy source, two hide it`() {
        val healthy = SourceCheckCache.merge(null, "wifi", 100, mapOf("a" to true))
        val first = SourceCheckCache.merge(healthy, "wifi", 200, mapOf("a" to false))
        assertEquals(SourceCheckEntry(true, 1), first.entries["a"])
        val second = SourceCheckCache.merge(first, "wifi", 300, mapOf("a" to false))
        assertEquals(SourceCheckEntry(false, 2), second.entries["a"])
        assertEquals(SourceCheckEntry(true), SourceCheckCache.merge(second, "wifi", 400, mapOf("a" to true)).entries["a"])
        assertEquals(SourceCheckEntry(false, 1), SourceCheckCache.merge(healthy, "mobile", 200, mapOf("a" to false)).entries["a"])
    }

    @Test fun `cache is scoped by network and expires after thirty minutes`() {
        val snapshot = SourceCheckSnapshot("wifi", 1000, mapOf("a" to SourceCheckEntry(true, 1)))
        assertTrue(snapshot.isFresh(1000 + 29 * 60_000))
        assertFalse(snapshot.isFresh(1000 + 30 * 60_000))
        assertFalse(snapshot.isFresh(999))
        assertEquals(listOf(snapshot), SourceCheckCache.decode(SourceCheckCache.encode(listOf(snapshot))))
        assertTrue(SourceCheckCache.decode("broken").isEmpty())
    }
}
