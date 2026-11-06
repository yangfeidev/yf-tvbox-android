package com.yangfei.xingmu.data.net

import com.yangfei.xingmu.data.model.Site
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteSpiderTest {
    @Test fun `json cms sends category using detail protocol`() {
        val server = MockWebServer()
        server.start()
        try {
            val site = Site("cms", "cms", server.url("/vod").toString(), 1, true, true)
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"影片"}]}"""))
            assertEquals("影片", VodApi.list(site, "movie").items.single().name)
            val request = server.takeRequest().requestUrl!!
            assertEquals("detail", request.queryParameter("ac"))
            assertEquals("movie", request.queryParameter("t"))
        } finally { server.shutdown() }
    }
    @Test fun `remote spider without home videos loads first category`() {
        val server = MockWebServer()
        server.start()
        try {
            val site = Site("remote", "remote", server.url("/api").toString(), 4, true, true)
            server.enqueue(MockResponse().setBody("""{"class":[{"type_id":"movie","type_name":"电影"}]}"""))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"影片"}]}"""))
            val page = VodApi.list(site)
            assertEquals("影片", page.items.single().name)
            assertEquals("电影", page.categories.single().name)
            server.takeRequest()
            assertEquals("movie", server.takeRequest().requestUrl!!.queryParameter("t"))
        } finally { server.shutdown() }
    }

    @Test fun `remote spider browsing and playback protocol`() {
        val server = MockWebServer()
        server.start()
        try {
            val site = Site("remote", "remote", server.url("/api?token=secret").toString(), 4, true, true, "config")
            assertTrue(site.usable)
            server.enqueue(MockResponse().setBody("""{"class":[{"type_id":"1","type_name":"电影"}],"list":[{"vod_id":"a","vod_name":"测试"}]}"""))
            assertEquals("电影", VodApi.categories(site).single().name)
            assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("filter"))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"a","vod_name":"测试","vod_pic":"/posters/a.jpg"}]}"""))
            val categoryItems = VodApi.list(site, "1", 2).items
            assertEquals("测试", categoryItems.single().name)
            assertEquals(server.url("/posters/a.jpg").toString(), categoryItems.single().poster)
            val category = server.takeRequest().requestUrl!!
            assertEquals("secret", category.queryParameter("token"))
            assertEquals("detail", category.queryParameter("ac"))
            assertEquals("2", category.queryParameter("pg"))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"a","vod_name":"测试"}]}"""))
            VodApi.search(site, "测试")
            assertEquals("测试", server.takeRequest().requestUrl!!.queryParameter("wd"))
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"a","vod_name":"测试","vod_play_from":"line","vod_play_url":"第一集\u0024opaque-id"}]}"""))
            assertEquals("opaque-id", VodApi.detail(site, "a").playSources.single().episodes.single().url)
            assertEquals("a", server.takeRequest().requestUrl!!.queryParameter("ids"))
            server.enqueue(MockResponse().setBody("""{"parse":0,"url":"https://video.example/a.m3u8","header":{"Referer":"https://video.example/"}}"""))
            val playback = VodApi.resolvePlayback(site, "line", "opaque-id")
            assertEquals("https://video.example/a.m3u8", playback.url)
            assertEquals("https://video.example/", playback.headers["Referer"])
            val play = server.takeRequest().requestUrl!!
            assertEquals("line", play.queryParameter("flag"))
            assertEquals("opaque-id", play.queryParameter("play"))
            server.enqueue(MockResponse().setBody("""{"parse":1,"url":"https://video.example/page"}"""))
            try { VodApi.resolvePlayback(site, "line", "id"); fail("parse should be rejected") } catch (e: SourceException) { assertTrue(e.message!!.contains("解析")) }
        } finally { server.shutdown() }
    }
    @Test fun `parent category gathers child pages and removes duplicates`() {
        val server = MockWebServer()
        server.start()
        try {
            val site = Site("cms", "cms", server.url("/vod").toString(), 1, true, true)
            val categories = VodParser.parseList("""{"class":[{"type_id":"1","type_name":"电影"},{"type_id":"2","type_name":"恐怖片","type_pid":1},{"type_id":"3","type_name":"喜剧片","type_pid":1}]}""", true).categories
            assertEquals("1", categories[1].parentId)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.requestUrl?.queryParameter("t") == "2")
                    MockResponse().setBody("""{"page":1,"pagecount":2,"list":[{"vod_id":"a","vod_name":"影片"}]}""")
                else MockResponse().setBody("""{"page":1,"pagecount":1,"list":[{"vod_id":"a","vod_name":"影片"},{"vod_id":"b","vod_name":"喜剧"}]}""")
            }
            val page = runBlocking { VodApi.listChildren(site, "1", categories, 1) }
            assertEquals(listOf("a", "b"), page.items.map { it.id })
            assertEquals(2, page.pageCount)
            assertEquals(setOf("2", "3"), setOf(server.takeRequest().requestUrl!!.queryParameter("t"), server.takeRequest().requestUrl!!.queryParameter("t")))
        } finally { server.shutdown() }
    }
}
