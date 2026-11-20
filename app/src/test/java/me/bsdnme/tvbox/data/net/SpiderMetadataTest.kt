package me.bsdnme.tvbox.data.net

import org.junit.Assert.*
import org.junit.Test

class SpiderMetadataTest {
    @Test fun `jar permissions are scoped to the configuration and bytes`() {
        val file = java.io.File("ignored")
        val first = SpiderRuntime.Prepared("https://jar.example/a", "a".repeat(64), file, "https://one.example/config")
        val second = first.copy(configUrl = "https://two.example/config")
        val updated = first.copy(sha256 = "b".repeat(64))
        assertNotEquals(first.permissionKey, second.permissionKey)
        assertNotEquals(first.permissionKey, updated.permissionKey)
        assertEquals(first.permissionKey, first.copy().permissionKey)
    }
    @Test fun `preserves jar origin overrides and script types without enabling execution`() {
        val doc = SubscriptionApi.parse("""{
          "spider":"../shared.jar;md5;0123",
          "sites":[
            {"key":"a","name":"默认","type":3,"api":"csp_Test","ext":{"url":"https://example.org"}},
            {"key":"b","name":"覆盖","type":3,"api":"csp_Test","jar":"./other.jar"},
            {"key":"c","name":"无效覆盖","type":3,"api":"csp_Test","jar":""},
            {"key":"d","name":"脚本","type":3,"api":"https://example.org/test.py"}
          ]
        }""", "https://config.example.org/path/config.json")
        assertEquals("https://config.example.org/shared.jar;md5;0123", doc.sites[0].jar)
        assertEquals("https://config.example.org/path/other.jar", doc.sites[1].jar)
        assertEquals("", doc.sites[2].jar)
        assertEquals("https://config.example.org/path/config.json", doc.sites[0].configUrl)
        assertEquals("https://example.org", org.json.JSONObject(doc.sites[0].ext).getString("url"))
        assertEquals(listOf("a", "b"), doc.usableSites.map { it.key })
    }
}
