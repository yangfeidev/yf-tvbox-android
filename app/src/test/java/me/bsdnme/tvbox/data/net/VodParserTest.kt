package me.bsdnme.tvbox.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodParserTest {
    @Test
    fun `list tolerates missing fields and ignores incomplete entries`() {
        val result = VodParser.parseList("""{"pagecount":0,"list":[{"vod_id":42,"vod_name":" 影片 "},{"vod_name":null},null],"class":[{"type_id":1,"type_name":"电影"},{"type_name":"无编号"}]}""", true)
        assertEquals(1, result.pageCount)
        assertEquals("42", result.items.single().id)
        assertEquals("影片", result.items.single().name)
        assertEquals("电影", result.categories.single().name)
    }

    @Test
    fun `detail splits sources and episodes and skips broken entries`() {
        val body = """{"list":[{"vod_id":"1","vod_name":"片名","vod_content":"<p>简介<br>下一行</p>","vod_play_from":"主线${'$'}${'$'}${'$'}备用","vod_play_url":"第一集${'$'}https://a.example/1.m3u8#坏数据#https://a.example/2.m3u8${'$'}${'$'}${'$'}第二集${'$'}https://b.example/2.m3u8"}]}"""
        val result = VodParser.parseDetail(body, true, "fallback")
        assertEquals("简介\n下一行", result.content)
        assertEquals(listOf("主线", "备用"), result.playSources.map { it.name })
        assertEquals(listOf("第一集", "第 2 集"), result.playSources.first().episodes.map { it.name })
        assertTrue(result.playSources.first().episodes.all { it.url.startsWith("https://") })
    }

    @Test(expected = SourceException::class)
    fun `invalid json returns source error`() {
        VodParser.parseList("not json", true)
    }

    @Test(expected = SourceException::class)
    fun `missing detail returns source error`() {
        VodParser.parseDetail("{}", true, "1")
    }
}
