package me.bsdnme.tvbox.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveParserTest {
    @Test
    fun `m3u preserves metadata and headers while filtering unsupported channels`() {
        val groups = LiveParser.parse("""
            \uFEFF#EXTM3U
            #EXTINF:-1 tvg-id="one" tvg-logo="logo" group-title="新闻",频道一
            #EXTVLCOPT:http-user-agent=Player
            https://example.com/one.m3u8
            #EXTINF:-1 group-title="新闻",更新时间
            https://example.com/fake
            #EXTINF:-1,IPv6
            http://[::1]/stream
        """.trimIndent().replace("\\uFEFF", "\uFEFF"))
        assertEquals(1, groups.size)
        assertEquals("新闻", groups.single().name)
        val channel = groups.single().channels.single()
        assertEquals("频道一", channel.name)
        assertEquals("one", channel.tvgId)
        assertEquals("logo", channel.logo)
        assertEquals("Player", channel.headers["User-Agent"])
    }

    @Test
    fun `txt groups valid entries and ignores malformed or unsupported rows`() {
        val groups = LiveParser.parse("""
            新闻,#genre#
            台一,https://example.com/one
            损坏行
            台二,udp://example.com/two
            卫视,#genre#
            台三,http://example.com/three
        """.trimIndent())
        assertEquals(listOf("新闻", "卫视"), groups.map { it.name })
        assertEquals(listOf("台一", "台三"), groups.flatMap { it.channels }.map { it.name })
        assertTrue(LiveParser.parse("  ").isEmpty())
    }
}
